package openfluxmobile

import (
	"context"
	"net/netip"
	"net/url"
	"testing"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
)

func TestCountersGrowOnTraffic(t *testing.T) {
	u, err := url.Parse("socks5://192.168.43.94:1080")
	if err != nil {
		t.Fatal(err)
	}
	inner, err := proxy.Parse(u)
	if err != nil {
		t.Fatal(err)
	}

	resetCounters()
	if Uploaded() != 0 || Downloaded() != 0 {
		t.Fatal("счётчики не обнулились")
	}

	// Цепочка ровно та же, что в Start: DNS-перехватчик поверх счётчика.
	p := &dnsOverTCPProxy{inner: &countingProxy{inner: inner}}

	meta := &M.Metadata{
		Network: M.TCP,
		DstIP:   netip.MustParseAddr("1.1.1.1"),
		DstPort: 80,
	}
	conn, err := p.DialContext(context.Background(), meta)
	if err != nil {
		t.Fatalf("DialContext: %v", err)
	}
	defer conn.Close()

	if _, err := conn.Write([]byte("GET / HTTP/1.0\r\nHost: one.one.one.one\r\n\r\n")); err != nil {
		t.Fatalf("Write: %v", err)
	}
	buf := make([]byte, 256)
	if _, err := conn.Read(buf); err != nil {
		t.Fatalf("Read: %v", err)
	}

	up, down := Uploaded(), Downloaded()
	if up == 0 {
		t.Fatal("отправленные байты не посчитаны")
	}
	if down == 0 {
		t.Fatal("полученные байты не посчитаны")
	}
	t.Logf("посчитано: отправлено %d, получено %d", up, down)
}

func TestDNSTrafficIsCounted(t *testing.T) {
	u, _ := url.Parse("socks5://192.168.43.94:1080")
	inner, err := proxy.Parse(u)
	if err != nil {
		t.Fatal(err)
	}

	resetCounters()
	p := &dnsOverTCPProxy{inner: &countingProxy{inner: inner}}

	meta := &M.Metadata{
		Network: M.UDP,
		DstIP:   netip.MustParseAddr("1.1.1.1"),
		DstPort: 53,
	}
	pc, err := p.DialUDP(meta)
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()

	dst := &netAddr{}
	if _, err := pc.WriteTo(buildQuery("example.com"), dst); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 1500)
	if _, _, err := pc.ReadFrom(buf); err != nil {
		t.Fatalf("ReadFrom: %v", err)
	}

	if Uploaded() == 0 || Downloaded() == 0 {
		t.Fatalf("DNS не попал в счётчики: up=%d down=%d", Uploaded(), Downloaded())
	}
	t.Logf("DNS учтён: отправлено %d, получено %d", Uploaded(), Downloaded())
}

// netAddr is a minimal UDP address for the DNS test.
type netAddr struct{}

func (a *netAddr) Network() string { return "udp" }
func (a *netAddr) String() string  { return "1.1.1.1:53" }
