package core

import (
	"encoding/base64"
	"net/netip"
	"strings"
	"testing"
)

func TestValidate(t *testing.T) {
	key := base64.StdEncoding.EncodeToString([]byte("01234567890123456789012345678901"))
	text := "[Interface]\nPrivateKey = " + key + "\nAddress = 10.77.0.1/30\nTable = off\n" +
		"[Peer]\nPublicKey = " + key + "\nEndpoint = vpn.invalid:51820\nAllowedIPs = 10.77.0.2/30, 0.0.0.0/0\n"
	for _, host := range []string{"", "127.0.0.1", "adb.invalid"} {
		if err := Validate(text, host); err != nil {
			t.Fatalf("offline validation for host %q failed: %v", host, err)
		}
	}
	for _, host := range []string{"localhost:5555", "http://adb.invalid", "::1", key} {
		_, wantErr := ADBAddress(host)
		if err := Validate(text, host); err == nil || err.Error() != wantErr.Error() {
			t.Fatal("validation must reuse the redacted ADB host error")
		}
	}
	badConfig := text + key + " = secret\n"
	_, wantErr := parseConfig(badConfig)
	if err := Validate(badConfig, ""); err == nil || err.Error() != wantErr.Error() || strings.Contains(err.Error(), key) {
		t.Fatal("validation must reuse the redacted configuration error")
	}
}

func TestConfig(t *testing.T) {
	private := base64.StdEncoding.EncodeToString([]byte("01234567890123456789012345678901"))
	public := base64.StdEncoding.EncodeToString([]byte("12345678901234567890123456789012"))
	text := "[Interface]\nPrivateKey = " + private + "\nAddress = 10.77.0.1/32\n" +
		"[Peer]\nPublicKey = " + public + "\nEndpoint = vpn.example.org:51820\nAllowedIPs = 10.77.0.2/32\n"

	cfg, err := parseConfig(text)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.address != netip.MustParseAddr("10.77.0.1") || len(cfg.allowedIPs) != 1 || cfg.allowedIPs[0] != netip.MustParsePrefix("10.77.0.2/32") ||
		cfg.endpointHost != "vpn.example.org" || cfg.endpointPort != 51820 || cfg.keepalive != 25 {
		t.Fatal("parsed configuration differs from requested network or default keepalive")
	}
	cfg, err = parseConfig(strings.ReplaceAll(text, "vpn.example.org", "192.0.2.4") + "PresharedKey = " + private + "\nPersistentKeepalive = 0\n")
	if err != nil || cfg.keepalive != 0 || cfg.presharedKey == nil {
		t.Fatal("IPv4 endpoint, preshared key, and disabled keepalive should be accepted")
	}

	accepted := map[string]struct {
		address, allowedIPs, table string
		wantAllowedIPs             []string
	}{
		"subnet":          {"10.77.0.1/30", "10.77.0.2/30", "", []string{"10.77.0.0/30"}},
		"zero prefix":     {"10.77.0.1/0", "0.0.0.0/0", "", []string{"0.0.0.0/0"}},
		"multiple routes": {"10.77.0.1/32", "10.77.0.3/24 , 127.0.0.1/8, 192.0.2.3/32", "", []string{"10.77.0.0/24", "127.0.0.0/8", "192.0.2.3/32"}},
		"same address":    {"10.77.0.1/32", "10.77.0.1/32", "", []string{"10.77.0.1/32"}},
		"table off":       {"10.77.0.1/30", "10.77.0.2/30", "Table = off\n", []string{"10.77.0.0/30"}},
	}
	for name, tc := range accepted {
		t.Run(name, func(t *testing.T) {
			input := strings.NewReplacer("10.77.0.1/32", tc.address, "10.77.0.2/32", tc.allowedIPs,
				"[Interface]\n", "[Interface]\n"+tc.table).Replace(text)
			cfg, err := parseConfig(input)
			if err != nil {
				t.Fatal(err)
			}
			if cfg.address != netip.MustParseAddr("10.77.0.1") {
				t.Fatal("interface host address was masked")
			}
			if len(cfg.allowedIPs) != len(tc.wantAllowedIPs) {
				t.Fatalf("unexpected allowed IPs: %v", cfg.allowedIPs)
			}
			for i, want := range tc.wantAllowedIPs {
				if cfg.allowedIPs[i].String() != want {
					t.Fatalf("allowed IP %d = %s, want %s", i, cfg.allowedIPs[i], want)
				}
			}
		})
	}

	rejected := map[string]string{
		"duplicate field":      text + "PublicKey = " + private + "\n",
		"duplicate routes":     text + "AllowedIPs = 192.0.2.0/24\n",
		"duplicate table":      strings.ReplaceAll(text, "[Interface]\n", "[Interface]\nTable = off\nTable = off\n"),
		"extra peer":           text + "[Peer]\nPublicKey = " + private + "\n",
		"extra interface":      text + "[Interface]\nAddress = 10.77.0.3/32\n",
		"hook":                 strings.ReplaceAll(text, "[Interface]\n", "[Interface]\nPostUp = "+private+"\n"),
		"unknown field":        text + private + " = anything\n",
		"IPv6 address":         strings.ReplaceAll(text, "10.77.0.1/32", "fd00::1/128"),
		"IPv6 peer":            strings.ReplaceAll(text, "10.77.0.2/32", "fd00::2/128"),
		"mixed IPv6 routes":    strings.ReplaceAll(text, "10.77.0.2/32", "10.77.0.2/32, ::/0"),
		"IPv6 endpoint":        strings.ReplaceAll(text, "vpn.example.org:51820", "[::1]:51820"),
		"multiple addresses":   strings.ReplaceAll(text, "10.77.0.1/32", "10.77.0.1/30, 10.77.0.2/30"),
		"unspecified address":  strings.ReplaceAll(text, "10.77.0.1/32", "0.0.0.0/0"),
		"loopback address":     strings.ReplaceAll(text, "10.77.0.1/32", "127.0.0.1/8"),
		"multicast address":    strings.ReplaceAll(text, "10.77.0.1/32", "224.0.0.1/4"),
		"invalid address mask": strings.ReplaceAll(text, "10.77.0.1/32", "10.77.0.1/33"),
		"invalid route mask":   strings.ReplaceAll(text, "10.77.0.2/32", "10.77.0.2/33"),
		"route without mask":   strings.ReplaceAll(text, "10.77.0.2/32", "10.77.0.2"),
		"empty first route":    strings.ReplaceAll(text, "10.77.0.2/32", ", 10.77.0.2/32"),
		"empty middle route":   strings.ReplaceAll(text, "10.77.0.2/32", "10.77.0.2/32, , 192.0.2.0/24"),
		"empty last route":     strings.ReplaceAll(text, "10.77.0.2/32", "10.77.0.2/32,"),
		"bad route redacted":   strings.ReplaceAll(text, "10.77.0.2/32", "10.77.0.2/32, "+private),
		"table auto":           strings.ReplaceAll(text, "[Interface]\n", "[Interface]\nTable = auto\n"),
		"numeric table":        strings.ReplaceAll(text, "[Interface]\n", "[Interface]\nTable = 123\n"),
		"bad table redacted":   strings.ReplaceAll(text, "[Interface]\n", "[Interface]\nTable = "+private+"\n"),
		"misplaced table":      text + "Table = off\n",
		"missing key":          strings.ReplaceAll(text, "PrivateKey = "+private+"\n", ""),
		"bad key":              strings.ReplaceAll(text, private, private+"!"),
		"bad port":             strings.ReplaceAll(text, ":51820", ":70000"),
		"bad keepalive":        text + "PersistentKeepalive = 65536\n",
	}
	for name, input := range rejected {
		t.Run(name, func(t *testing.T) {
			_, err := parseConfig(input)
			if err == nil {
				t.Fatal("unsupported configuration was accepted")
			}
			if strings.Contains(err.Error(), private) || strings.Contains(err.Error(), public) {
				t.Fatal("configuration error exposed key material")
			}
		})
	}
}
