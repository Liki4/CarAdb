package core

import (
	"io"
	"net"
)

const maxConnections = 16

type forward struct {
	remote net.Conn
	local  net.Conn // assignment and shutdown are protected by session.mu
}

func (f *forward) close() {
	f.remote.Close()
	if f.local != nil {
		f.local.Close()
	}
}

func (e *Engine) accept(r *session) {
	defer r.wg.Done()
	for {
		remote, err := r.listener.Accept()
		if err != nil {
			if r.ctx.Err() == nil {
				e.fail(r, "virtual TCP listener stopped unexpectedly")
			}
			return
		}
		r.mu.Lock()
		if r.ctx.Err() != nil || len(r.active) >= maxConnections {
			r.mu.Unlock()
			remote.Close()
			continue
		}
		f := &forward{remote: remote}
		r.active[f] = struct{}{}
		r.wg.Add(1)
		r.mu.Unlock()
		go r.serve(f)
	}
}

func (r *session) serve(f *forward) {
	defer r.wg.Done()
	defer func() {
		r.mu.Lock()
		f.close()
		delete(r.active, f)
		r.mu.Unlock()
	}()

	local, err := (&net.Dialer{Timeout: dialTimeout}).DialContext(r.ctx, "tcp4", r.targetAddr)
	r.mu.Lock()
	if err != nil || r.ctx.Err() != nil {
		if local != nil {
			local.Close()
		}
		if r.ctx.Err() == nil {
			r.proxyError = "cannot connect to local ADB target within 5 seconds"
		}
		r.mu.Unlock()
		return
	}
	f.local, r.proxyError = local, ""
	r.mu.Unlock()

	// io.Copy gives bounded buffering and TCP backpressure. An EOF shuts down
	// only the destination's write side, preserving the reverse response stream.
	// A real I/O error closes both sides so the other copier cannot hang.
	copyHalf := func(dst, src net.Conn) {
		_, err := io.Copy(dst, src)
		if err == nil {
			err = dst.(interface{ CloseWrite() error }).CloseWrite()
		}
		if err != nil {
			f.close()
		}
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		copyHalf(local, f.remote)
	}()
	copyHalf(f.remote, local)
	<-done
}
