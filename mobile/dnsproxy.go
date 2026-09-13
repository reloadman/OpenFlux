package openfluxmobile

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"os"
	"sync"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
)

// dnsTimeout bounds a single DNS exchange over the tunnel.
const dnsTimeout = 10 * time.Second

// dnsOverTCPProxy wraps a proxy and turns UDP DNS queries into TCP ones.
type dnsOverTCPProxy struct {
	inner proxy.Proxy
}

// DialContext passes TCP traffic through untouched.
func (p *dnsOverTCPProxy) DialContext(ctx context.Context, m *M.Metadata) (net.Conn, error) {
	return p.inner.DialContext(ctx, m)
}

// DialUDP intercepts port 53 and leaves every other datagram alone.
func (p *dnsOverTCPProxy) DialUDP(m *M.Metadata) (net.PacketConn, error) {
	if m.DstPort != 53 {
		return p.inner.DialUDP(m)
	}
	// В момент ограничений оператор резолвит только домены из белого списка,
	// поэтому UDP-запрос к его резолверу вернёт подделку или вовсе ничего.
	// RFC 7766 разрешает тот же запрос по TCP, а TCP уходит внутрь туннеля.
	return &dnsPacketConn{
		inner:   p.inner,
		base:    *m,
		replies: make(chan dnsReply, 16),
		done:    make(chan struct{}),
	}, nil
}

type dnsReply struct {
	data []byte
	addr net.Addr
}

// dnsPacketConn looks like a UDP socket but resolves over TCP.
type dnsPacketConn struct {
	inner   proxy.Proxy
	base    M.Metadata
	replies chan dnsReply
	done    chan struct{}
	closed  sync.Once

	mu       sync.Mutex
	deadline time.Time
}

// WriteTo accepts a query and resolves it in the background.
func (c *dnsPacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	query := make([]byte, len(b))
	copy(query, b)
	go c.resolve(query, addr)
	return len(b), nil
}

// resolve performs one DNS exchange framed per RFC 7766.
func (c *dnsPacketConn) resolve(query []byte, addr net.Addr) {
	meta := c.base
	meta.Network = M.TCP
	if ua, ok := addr.(*net.UDPAddr); ok && ua != nil {
		if ip, ok := netip.AddrFromSlice(ua.IP); ok {
			meta.DstIP = ip.Unmap()
			meta.DstPort = uint16(ua.Port)
		}
	}

	ctx, cancel := context.WithTimeout(context.Background(), dnsTimeout)
	defer cancel()
	conn, err := c.inner.DialContext(ctx, &meta)
	if err != nil {
		return
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(dnsTimeout))

	framed := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(framed[:2], uint16(len(query)))
	copy(framed[2:], query)
	if _, err := conn.Write(framed); err != nil {
		return
	}

	var header [2]byte
	if _, err := io.ReadFull(conn, header[:]); err != nil {
		return
	}
	size := int(binary.BigEndian.Uint16(header[:]))
	if size == 0 {
		return
	}
	answer := make([]byte, size)
	if _, err := io.ReadFull(conn, answer); err != nil {
		return
	}

	select {
	case c.replies <- dnsReply{data: answer, addr: addr}:
	case <-c.done:
	}
}

// ReadFrom hands the answer back to the tun stack.
func (c *dnsPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	c.mu.Lock()
	deadline := c.deadline
	c.mu.Unlock()

	var expired <-chan time.Time
	if !deadline.IsZero() {
		timer := time.NewTimer(time.Until(deadline))
		defer timer.Stop()
		expired = timer.C
	}

	select {
	case reply := <-c.replies:
		return copy(b, reply.data), reply.addr, nil
	case <-expired:
		return 0, nil, os.ErrDeadlineExceeded
	case <-c.done:
		return 0, nil, net.ErrClosed
	}
}

func (c *dnsPacketConn) Close() error {
	c.closed.Do(func() { close(c.done) })
	return nil
}

func (c *dnsPacketConn) LocalAddr() net.Addr {
	return &net.UDPAddr{IP: net.IPv4zero, Port: 0}
}

func (c *dnsPacketConn) SetDeadline(t time.Time) error {
	return c.SetReadDeadline(t)
}

func (c *dnsPacketConn) SetReadDeadline(t time.Time) error {
	c.mu.Lock()
	c.deadline = t
	c.mu.Unlock()
	return nil
}

func (c *dnsPacketConn) SetWriteDeadline(time.Time) error {
	return nil
}
