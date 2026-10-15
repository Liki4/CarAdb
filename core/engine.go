package core

import (
	"bytes"
	"context"
	"encoding/hex"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

const dialTimeout = 5 * time.Second

// Snapshot contains only public counters, never WireGuard keys or UAPI output.
// Running means a handshake has occurred; LastHandshake exposes its age.
type Snapshot struct {
	State         string `json:"state"`
	LastHandshake int64  `json:"lastHandshake"`
	RxBytes       uint64 `json:"rxBytes"`
	TxBytes       uint64 `json:"txBytes"`
	Connections   int    `json:"connections"`
	Error         string `json:"error"`
}

// Engine's zero value is ready to use. It owns at most one userspace tunnel.
type Engine struct {
	mu        sync.Mutex
	current   *session
	state     string
	lastError string
}

type session struct {
	ctx     context.Context
	cancel  context.CancelFunc
	started chan struct{}
	done    chan struct{}
	wg      sync.WaitGroup

	mu         sync.Mutex
	dev        *device.Device
	listener   net.Listener
	active     map[*forward]struct{}
	proxyError string
	targetAddr string
}

// Start accepts an internal target address so host tests can use an ephemeral
// TCP service. The mobile bridge always supplies port 5555.
func (e *Engine) Start(configText, targetAddr string) error {
	e.mu.Lock()
	if e.current != nil {
		e.mu.Unlock()
		return errors.New("tunnel is already started or stopping")
	}
	ctx, cancel := context.WithCancel(context.Background())
	r := &session{ctx: ctx, cancel: cancel, started: make(chan struct{}), done: make(chan struct{}),
		active: make(map[*forward]struct{}), targetAddr: targetAddr}
	e.current, e.state, e.lastError = r, "starting", ""
	e.mu.Unlock()
	go e.cleanupWhenStopped(r)

	if err := r.setup(configText); err != nil {
		e.fail(r, err.Error())
		close(r.started)
		<-r.done
		return err
	}
	e.mu.Lock()
	if r.ctx.Err() != nil {
		e.mu.Unlock()
		close(r.started)
		<-r.done
		return errors.New("tunnel start canceled")
	}
	e.state = "waiting"
	r.wg.Add(1)
	go e.accept(r)
	e.mu.Unlock()
	close(r.started)
	return nil
}

func (r *session) setup(text string) error {
	cfg, err := parseConfig(text)
	if err != nil {
		return err
	}
	host, portText, err := net.SplitHostPort(r.targetAddr)
	port, portErr := strconv.ParseUint(portText, 10, 16)
	if err != nil || portErr != nil || port == 0 || !validHost(host) {
		return errors.New("invalid local TCP target")
	}
	endpoint, err := resolveEndpoint(r.ctx, cfg.endpointHost, cfg.endpointPort)
	if err != nil {
		return err
	}
	if r.ctx.Err() != nil {
		return errors.New("tunnel start canceled")
	}
	tun, tnet, err := netstack.CreateNetTUN([]netip.Addr{cfg.address}, nil, 1280)
	if err != nil {
		return errors.New("cannot create userspace network stack")
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	r.mu.Lock()
	r.dev = dev
	r.mu.Unlock()
	ipc := "private_key=" + hex.EncodeToString(cfg.privateKey[:]) + "\nreplace_peers=true\npublic_key=" +
		hex.EncodeToString(cfg.publicKey[:]) + "\nendpoint=" + endpoint.String() +
		"\npersistent_keepalive_interval=" + strconv.Itoa(int(cfg.keepalive)) + "\n"
	for _, prefix := range cfg.allowedIPs {
		ipc += "allowed_ip=" + prefix.String() + "\n"
	}
	if cfg.presharedKey != nil {
		ipc += "preshared_key=" + hex.EncodeToString(cfg.presharedKey[:]) + "\n"
	}
	if err := dev.IpcSet(ipc); err != nil {
		return errors.New("cannot configure WireGuard device")
	}
	listener, err := tnet.ListenTCP(&net.TCPAddr{IP: net.IP(cfg.address.AsSlice()), Port: 15555})
	if err != nil {
		return errors.New("cannot listen on virtual TCP port 15555")
	}
	r.mu.Lock()
	r.listener = listener
	r.mu.Unlock()
	if r.ctx.Err() != nil {
		return errors.New("tunnel start canceled")
	}
	if err := dev.Up(); err != nil {
		return errors.New("cannot start WireGuard UDP transport")
	}
	return nil
}

func resolveEndpoint(ctx context.Context, host string, port uint16) (netip.AddrPort, error) {
	if ip, err := netip.ParseAddr(host); err == nil && ip.Is4() {
		return netip.AddrPortFrom(ip, port), nil
	}
	ctx, cancel := context.WithTimeout(ctx, dialTimeout)
	defer cancel()
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip4", host)
	if err != nil || len(ips) == 0 {
		return netip.AddrPort{}, errors.New("cannot resolve WireGuard endpoint to IPv4 within 5 seconds")
	}
	return netip.AddrPortFrom(ips[0].Unmap(), port), nil
}

func (e *Engine) fail(r *session, message string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.current == r && r.ctx.Err() == nil {
		e.state, e.lastError = "error", message
	}
	r.cancel()
}

func (e *Engine) cleanupWhenStopped(r *session) {
	<-r.ctx.Done()
	<-r.started // startup alone owns resource creation until this point
	r.shutdown()
	e.mu.Lock()
	if e.current == r {
		e.current = nil
		if e.state != "error" {
			e.state, e.lastError = "stopped", ""
		}
	}
	close(r.done)
	e.mu.Unlock()
}

// Stop cancels startup DNS and local dials, closes all connections and the
// virtual listener, then releases the WireGuard device (including TUN and UDP).
// Concurrent and repeated calls wait for the same complete shutdown.
func (e *Engine) Stop() {
	e.mu.Lock()
	r := e.current
	if r == nil {
		e.state, e.lastError = "stopped", ""
		e.mu.Unlock()
		return
	}
	e.state = "stopping"
	r.cancel()
	e.mu.Unlock()
	<-r.done
}

func (r *session) shutdown() {
	if r.listener != nil {
		r.listener.Close()
	}
	r.mu.Lock()
	for f := range r.active {
		f.close()
	}
	r.mu.Unlock()
	r.wg.Wait()
	if r.dev != nil {
		r.dev.Close() // device owns and closes its netstack TUN and UDP bind
	}
}

func (e *Engine) Status() Snapshot {
	e.mu.Lock()
	defer e.mu.Unlock()
	s := Snapshot{State: e.state, Error: e.lastError}
	if s.State == "" {
		s.State = "stopped"
	}
	if r := e.current; r != nil {
		r.mu.Lock()
		s.Connections = len(r.active)
		if s.Error == "" {
			s.Error = r.proxyError
		}
		dev := r.dev
		r.mu.Unlock()
		if dev != nil {
			// Consume only the three public counters. Never return, store, or log
			// IpcGet's raw string: it includes the private and preshared keys.
			if err := dev.IpcGetOperation(counterWriter{&s}); err != nil {
				s.Error = "cannot read WireGuard counters"
			}
		}
		if s.State == "waiting" && s.LastHandshake > 0 {
			s.State = "running"
		}
	}
	return s
}

type counterWriter struct{ status *Snapshot }

func (w counterWriter) Write(data []byte) (int, error) {
	n := len(data)
	for len(data) > 0 {
		var line []byte
		line, data, _ = bytes.Cut(data, []byte{'\n'})
		name, value, _ := bytes.Cut(line, []byte{'='})
		switch string(name) {
		case "last_handshake_time_sec":
			w.status.LastHandshake, _ = strconv.ParseInt(string(value), 10, 64)
		case "rx_bytes":
			w.status.RxBytes, _ = strconv.ParseUint(string(value), 10, 64)
		case "tx_bytes":
			w.status.TxBytes, _ = strconv.ParseUint(string(value), 10, 64)
		}
	}
	return n, nil
}

// ADBAddress validates a host-only UI value and fixes the local ADB port.
func ADBAddress(host string) (string, error) {
	if host == "" {
		host = "127.0.0.1"
	}
	if !validHost(host) {
		return "", errors.New("ADB host must be an IPv4 address or hostname without a port")
	}
	return net.JoinHostPort(host, "5555"), nil
}

// Probe checks local TCP reachability only; it does not perform ADB authentication.
func Probe(adbHost string) error {
	addr, err := ADBAddress(adbHost)
	if err != nil {
		return err
	}
	c, err := net.DialTimeout("tcp4", addr, dialTimeout)
	if err != nil {
		return errors.New("cannot connect to local ADB on port 5555 within 5 seconds")
	}
	c.Close()
	return nil
}
