// Package mobile is the small gomobile boundary owned by the Android foreground
// service. The Go core keeps configuration and keys in memory; Android owns persistence.
package mobile

import (
	"encoding/json"

	"caradb/core"
)

var engine core.Engine

// Validate checks configuration and the local ADB host without DNS or network I/O.
func Validate(configText, adbHost string) error {
	return core.Validate(configText, adbHost)
}

// Start forwards virtual IPv4:15555 to the selected local host on TCP port 5555.
// An empty adbHost selects 127.0.0.1. Call off the Android main thread.
func Start(configText string, adbHost string) error {
	addr, err := core.ADBAddress(adbHost)
	if err != nil {
		return err
	}
	return engine.Start(configText, addr)
}

// Stop is safe to call concurrently, including while Start is resolving DNS.
func Stop() {
	engine.Stop()
}

// Status exposes only state, lastHandshake, rxBytes, txBytes, connections, error.
// A running state means a handshake occurred, not that it is still fresh.
func Status() string {
	data, _ := json.Marshal(engine.Status())
	return string(data)
}

// Probe tests local TCP reachability, not the ADB protocol or authorization.
func Probe(adbHost string) error {
	return core.Probe(adbHost)
}
