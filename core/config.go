package core

import (
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

type config struct {
	privateKey   [32]byte
	publicKey    [32]byte
	presharedKey *[32]byte
	address      netip.Addr
	allowedIPs   []netip.Prefix
	endpointHost string
	endpointPort uint16
	keepalive    uint16
}

// Validate checks configuration and the local ADB host without DNS or network I/O.
func Validate(configText, adbHost string) error {
	if _, err := parseConfig(configText); err != nil {
		return err
	}
	_, err := ADBAddress(adbHost)
	return err
}

// Errors deliberately contain only fixed descriptions and line numbers, never
// user-supplied names or values (even an unknown field name can contain a key).
func parseConfig(text string) (config, error) {
	cfg := config{keepalive: 25}
	if len(text) > 64*1024 {
		return cfg, errors.New("configuration is too large")
	}
	section := ""
	sections, fields := map[string]bool{}, map[string]bool{}
	for i, raw := range strings.Split(text, "\n") {
		line, _, _ := strings.Cut(raw, "#")
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		bad := func(reason string) (config, error) {
			return config{}, fmt.Errorf("configuration line %d: %s", i+1, reason)
		}
		if strings.HasPrefix(line, "[") {
			if line != "[Interface]" && line != "[Peer]" {
				return bad("unsupported section")
			}
			if sections[line] {
				return bad("duplicate section; exactly one interface and peer are supported")
			}
			sections[line] = true
			section = line
			continue
		}
		name, value, ok := strings.Cut(line, "=")
		name, value = strings.TrimSpace(name), strings.TrimSpace(value)
		if !ok || value == "" {
			return bad("expected a nonempty field value")
		}
		allowed := section == "[Interface]" && (name == "PrivateKey" || name == "Address" || name == "Table") ||
			section == "[Peer]" && (name == "PublicKey" || name == "PresharedKey" || name == "Endpoint" || name == "AllowedIPs" || name == "PersistentKeepalive")
		if !allowed {
			return bad("unsupported or misplaced field")
		}
		if fields[name] {
			return bad("duplicate field")
		}
		fields[name] = true
		switch name {
		case "PrivateKey", "PublicKey", "PresharedKey":
			decoded, err := base64.StdEncoding.Strict().DecodeString(value)
			if err != nil || len(decoded) != 32 || len(value) != 44 {
				return bad("keys must be base64-encoded 32-byte values")
			}
			var key [32]byte
			copy(key[:], decoded)
			if key == [32]byte{} && name != "PresharedKey" {
				return bad("private and public keys must not be zero")
			}
			switch name {
			case "PrivateKey":
				cfg.privateKey = key
			case "PublicKey":
				cfg.publicKey = key
			case "PresharedKey":
				cfg.presharedKey = &key
			}
		case "Address":
			prefix, err := netip.ParsePrefix(value)
			if err != nil || !prefix.Addr().Is4() || !prefix.Addr().IsGlobalUnicast() {
				return bad("address must be a single unicast IPv4 CIDR")
			}
			cfg.address = prefix.Addr()
		case "AllowedIPs":
			for _, item := range strings.Split(value, ",") {
				prefix, err := netip.ParsePrefix(strings.TrimSpace(item))
				if err != nil || !prefix.Addr().Is4() {
					return bad("allowed IPs must be comma-separated IPv4 prefixes")
				}
				cfg.allowedIPs = append(cfg.allowedIPs, prefix.Masked())
			}
		case "Table":
			if value != "off" {
				return bad("only Table = off is supported")
			}
			// No-op: this userspace tunnel never installs system routes.
		case "Endpoint":
			host, portText, err := net.SplitHostPort(value)
			port, portErr := strconv.ParseUint(portText, 10, 16)
			if err != nil || portErr != nil || port == 0 || !validHost(host) {
				return bad("endpoint must be an IPv4 address or hostname with a port from 1 to 65535")
			}
			cfg.endpointHost, cfg.endpointPort = host, uint16(port)
		case "PersistentKeepalive":
			n, err := strconv.ParseUint(value, 10, 16)
			if err != nil {
				return bad("keepalive must be an integer from 0 to 65535")
			}
			cfg.keepalive = uint16(n)
		}
	}
	for _, required := range []string{"PrivateKey", "Address", "PublicKey", "Endpoint", "AllowedIPs"} {
		if !fields[required] {
			return config{}, fmt.Errorf("configuration is missing %s", required)
		}
	}
	return cfg, nil
}

// validHost accepts only an IPv4 literal or an ASCII DNS hostname, without a
// scheme, port, path, or IPv6 zone. This also keeps generated UAPI input inert.
func validHost(host string) bool {
	if ip, err := netip.ParseAddr(host); err == nil {
		return ip.Is4()
	}
	host = strings.TrimSuffix(host, ".")
	if len(host) == 0 || len(host) > 253 {
		return false
	}
	onlyDigits := true
	for _, label := range strings.Split(host, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '-' {
				onlyDigits = false
			} else if c < '0' || c > '9' {
				return false
			}
		}
	}
	return !onlyDigits
}
