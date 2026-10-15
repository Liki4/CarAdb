package core

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// A real UDP WireGuard peer reaches a real local TCP service via the userspace
// listener. The service deliberately waits for EOF before sending its response.
func TestWireGuardTCPForward(t *testing.T) {
	for _, tc := range []struct {
		name, address, allowedIPs, table string
		wantAllowedIPs                   []string
	}{
		{"host route", "10.77.0.1/32", "10.77.0.2/32", "", []string{"10.77.0.2/32"}},
		{"subnet routes", "10.77.0.1/30", "10.77.0.2/30, 192.0.2.3/24, 127.0.0.1/8, 0.0.0.0/0", "Table = off\n",
			[]string{"10.77.0.0/30", "192.0.2.0/24", "127.0.0.0/8", "0.0.0.0/0"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			engineKey, err := ecdh.X25519().GenerateKey(rand.Reader)
			if err != nil {
				t.Fatal(err)
			}
			peerKey, err := ecdh.X25519().GenerateKey(rand.Reader)
			if err != nil {
				t.Fatal(err)
			}
			peerTun, peerNet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.2")}, nil, 1280)
			if err != nil {
				t.Fatal(err)
			}
			peer := device.NewDevice(peerTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
			t.Cleanup(peer.Close)
			if err := peer.IpcSet("private_key=" + hex.EncodeToString(peerKey.Bytes()) + "\nlisten_port=0\npublic_key=" +
				hex.EncodeToString(engineKey.PublicKey().Bytes()) + "\nallowed_ip=10.77.0.1/32\n"); err != nil {
				t.Fatal("cannot configure test WireGuard peer")
			}
			if err := peer.Up(); err != nil {
				t.Fatal(err)
			}
			ipc, err := peer.IpcGet()
			if err != nil {
				t.Fatal("cannot read test peer port")
			}
			var port string
			for _, line := range strings.Split(ipc, "\n") {
				if strings.HasPrefix(line, "listen_port=") {
					port = strings.TrimPrefix(line, "listen_port=")
				}
			}
			if port == "" || port == "0" {
				t.Fatal("test peer did not bind a UDP port")
			}
			text := "[Interface]\nPrivateKey = " + base64.StdEncoding.EncodeToString(engineKey.Bytes()) +
				"\nAddress = " + tc.address + "\n" + tc.table + "[Peer]\nPublicKey = " + base64.StdEncoding.EncodeToString(peerKey.PublicKey().Bytes()) +
				"\nEndpoint = 127.0.0.1:" + port + "\nAllowedIPs = " + tc.allowedIPs + "\n"

			local, err := net.Listen("tcp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { local.Close() })
			request := bytes.Repeat([]byte("ADB request across multiple WireGuard packets\n"), 4096)
			response := bytes.Repeat([]byte("ADB reply after request EOF\n"), 4096)
			served := make(chan error, 1)
			held := make(chan struct{})
			released := make(chan error, 1)
			go func() {
				c, err := local.Accept()
				if err != nil {
					served <- err
					return
				}
				c.SetDeadline(time.Now().Add(10 * time.Second))
				got, err := io.ReadAll(c)
				if err == nil && !bytes.Equal(got, request) {
					err = errors.New("local service received different bytes")
				}
				if err == nil {
					_, err = c.Write(response)
				}
				c.Close()
				served <- err
				c, err = local.Accept()
				if err != nil {
					released <- err
					return
				}
				defer c.Close()
				c.SetDeadline(time.Now().Add(10 * time.Second))
				close(held)
				var b [1]byte
				_, err = c.Read(b[:])
				released <- err
			}()

			var engine Engine
			t.Cleanup(engine.Stop)
			if engine.Status().State != "stopped" {
				t.Fatal("new engine must be stopped")
			}
			if err := engine.Start(text, local.Addr().String()); err != nil {
				t.Fatal(err)
			}
			if err := engine.Start(text, local.Addr().String()); err == nil {
				t.Fatal("duplicate start should fail")
			}
			engine.mu.Lock()
			ipc, err = engine.current.dev.IpcGet()
			engine.mu.Unlock()
			if err != nil {
				t.Fatal("cannot read configured WireGuard prefixes")
			}
			gotAllowedIPs := make(map[string]bool)
			for _, line := range strings.Split(ipc, "\n") {
				if prefix, ok := strings.CutPrefix(line, "allowed_ip="); ok {
					gotAllowedIPs[prefix] = true
				}
			}
			if len(gotAllowedIPs) != len(tc.wantAllowedIPs) {
				t.Fatalf("unexpected WireGuard prefixes: %v", gotAllowedIPs)
			}
			for _, prefix := range tc.wantAllowedIPs {
				if !gotAllowedIPs[prefix] {
					t.Fatalf("WireGuard prefix %s was not configured", prefix)
				}
			}
			waitFor(t, ctx, func() bool { return engine.Status().LastHandshake > 0 })

			remoteAddr := netip.MustParseAddrPort("10.77.0.1:15555")
			client, err := peerNet.DialContextTCPAddrPort(ctx, remoteAddr)
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			client.SetDeadline(time.Now().Add(10 * time.Second))
			if _, err := client.Write(request); err != nil {
				t.Fatal(err)
			}
			if err := client.CloseWrite(); err != nil {
				t.Fatal(err)
			}
			got, err := io.ReadAll(client)
			if err != nil || !bytes.Equal(got, response) {
				t.Fatalf("reply after TCP half-close differs or failed: %v", err)
			}
			if err := <-served; err != nil {
				t.Fatal(err)
			}
			waitFor(t, ctx, func() bool { return engine.Status().Connections == 0 })
			status := engine.Status()
			if status.State != "running" || status.RxBytes == 0 || status.TxBytes == 0 || status.Error != "" {
				t.Fatalf("unexpected status after forwarding: %+v", status)
			}

			active, err := peerNet.DialContextTCPAddrPort(ctx, remoteAddr)
			if err != nil {
				t.Fatal(err)
			}
			defer active.Close()
			select {
			case <-held:
			case <-ctx.Done():
				t.Fatal("second connection did not reach the local service")
			}
			if engine.Status().Connections != 1 {
				t.Fatal("active connection is not counted")
			}
			stopped := make(chan struct{}, 2)
			for range 2 {
				go func() { engine.Stop(); stopped <- struct{}{} }()
			}
			for range 2 {
				select {
				case <-stopped:
				case <-time.After(3 * time.Second):
					t.Fatal("Stop did not finish promptly")
				}
			}
			if err := <-released; !errors.Is(err, io.EOF) {
				t.Fatalf("Stop should close the local connection, got %v", err)
			}
			// Stop tears down the UDP tunnel too, so delivery of a final FIN to the
			// remote peer is not guaranteed. Local EOF and the stopped snapshot prove
			// cleanup; the completed first exchange already proves TCP half-close.
			active.Close()
			if status := engine.Status(); status.State != "stopped" || status.Connections != 0 {
				t.Fatalf("unexpected stopped status: %+v", status)
			}
			if err := engine.Start(text, local.Addr().String()); err != nil {
				t.Fatalf("restart failed: %v", err)
			}
			engine.Stop()
		})
	}
}

func waitFor(t *testing.T, ctx context.Context, condition func() bool) {
	t.Helper()
	for !condition() {
		select {
		case <-ctx.Done():
			t.Fatal(fmt.Errorf("timed out waiting for WireGuard forwarding: %w", ctx.Err()))
		case <-time.After(10 * time.Millisecond):
		}
	}
}
