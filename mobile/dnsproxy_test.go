package openfluxmobile

import (
	"net"
	"net/netip"
	"net/url"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
)

// buildQuery makes a minimal A-record request.
func buildQuery(name string) []byte {
	q := []byte{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	for _, label := range splitName(name) {
		q = append(q, byte(len(label)))
		q = append(q, label...)
	}
	return append(q, 0x00, 0x00, 0x01, 0x00, 0x01)
}

func splitName(name string) []string {
	var out []string
	start := 0
	for i := 0; i <= len(name); i++ {
		if i == len(name) || name[i] == '.' {
			if i > start {
				out = append(out, name[start:i])
			}
			start = i + 1
		}
	}
	return out
}

func TestDNSOverTCP(t *testing.T) {
	socks := "192.168.43.94:1080"
	u, err := url.Parse("socks5://" + socks)
	if err != nil {
		t.Fatal(err)
	}
	inner, err := proxy.Parse(u)
	if err != nil {
		t.Fatalf("не разобрал прокси: %v", err)
	}

	p := &dnsOverTCPProxy{inner: inner}
	meta := &M.Metadata{
		Network: M.UDP,
		DstIP:   netip.MustParseAddr("1.1.1.1"),
		DstPort: 53,
	}
	pc, err := p.DialUDP(meta)
	if err != nil {
		t.Fatalf("DialUDP: %v", err)
	}
	defer pc.Close()

	if _, ok := pc.(*dnsPacketConn); !ok {
		t.Fatal("порт 53 не перехвачен")
	}

	dst := &net.UDPAddr{IP: net.ParseIP("1.1.1.1"), Port: 53}
	if _, err := pc.WriteTo(buildQuery("example.com"), dst); err != nil {
		t.Fatalf("WriteTo: %v", err)
	}

	_ = pc.SetReadDeadline(time.Now().Add(20 * time.Second))
	buf := make([]byte, 1500)
	n, addr, err := pc.ReadFrom(buf)
	if err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}
	if n < 12 {
		t.Fatalf("слишком короткий ответ: %d", n)
	}
	if buf[0] != 0x12 || buf[1] != 0x34 {
		t.Fatalf("чужой идентификатор запроса: %x %x", buf[0], buf[1])
	}
	answers := int(buf[6])<<8 | int(buf[7])
	if answers == 0 {
		t.Fatal("в ответе нет записей")
	}
	t.Logf("получено %d байт от %v, записей: %d", n, addr, answers)
}

func TestNonDNSPassesThrough(t *testing.T) {
	u, _ := url.Parse("socks5://192.168.43.94:1080")
	inner, err := proxy.Parse(u)
	if err != nil {
		t.Fatal(err)
	}
	p := &dnsOverTCPProxy{inner: inner}
	meta := &M.Metadata{
		Network: M.UDP,
		DstIP:   netip.MustParseAddr("1.1.1.1"),
		DstPort: 443,
	}
	pc, err := p.DialUDP(meta)
	if err != nil {
		t.Skipf("SOCKS5 не поддерживает UDP, это ожидаемо: %v", err)
	}
	defer pc.Close()
	if _, ok := pc.(*dnsPacketConn); ok {
		t.Fatal("перехвачен не тот порт")
	}
}
