package openfluxmobile

import (
	"context"
	"net"
	"sync/atomic"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
)

var (
	bytesUp   atomic.Int64
	bytesDown atomic.Int64
)

// Uploaded returns bytes sent into the tunnel since Start.
func Uploaded() int64 {
	return bytesUp.Load()
}

// Downloaded returns bytes received from the tunnel since Start.
func Downloaded() int64 {
	return bytesDown.Load()
}

func resetCounters() {
	bytesUp.Store(0)
	bytesDown.Store(0)
}

// countingProxy tallies every byte that passes through the tunnel.
type countingProxy struct {
	inner proxy.Proxy
}

func (p *countingProxy) DialContext(ctx context.Context, m *M.Metadata) (net.Conn, error) {
	conn, err := p.inner.DialContext(ctx, m)
	if err != nil {
		return nil, err
	}
	return &countingConn{Conn: conn}, nil
}

func (p *countingProxy) DialUDP(m *M.Metadata) (net.PacketConn, error) {
	pc, err := p.inner.DialUDP(m)
	if err != nil {
		return nil, err
	}
	return &countingPacketConn{PacketConn: pc}, nil
}

type countingConn struct {
	net.Conn
}

func (c *countingConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 {
		bytesDown.Add(int64(n))
	}
	return n, err
}

func (c *countingConn) Write(b []byte) (int, error) {
	n, err := c.Conn.Write(b)
	if n > 0 {
		bytesUp.Add(int64(n))
	}
	return n, err
}

type countingPacketConn struct {
	net.PacketConn
}

func (c *countingPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, addr, err := c.PacketConn.ReadFrom(b)
	if n > 0 {
		bytesDown.Add(int64(n))
	}
	return n, addr, err
}

func (c *countingPacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	n, err := c.PacketConn.WriteTo(b, addr)
	if n > 0 {
		bytesUp.Add(int64(n))
	}
	return n, err
}
