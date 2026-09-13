// Package openfluxmobile bridges Android VpnService and the tunnel.
package openfluxmobile

import (
	"fmt"
	"sync"

	"github.com/xjasonlyu/tun2socks/v2/engine"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
)

var (
	mu      sync.Mutex
	started bool
)

// Start attaches the tun device to a SOCKS5 proxy.
func Start(fd int, socksAddr string, logLevel string) error {
	// VpnService отдаёт дескриптор tun-интерфейса, а openflux умеет только
	// SOCKS5. tun2socks читает пакеты из tun и заворачивает их в этот прокси.
	mu.Lock()
	defer mu.Unlock()
	if started {
		return nil
	}
	if logLevel == "" {
		logLevel = "warning"
	}
	key := &engine.Key{
		Device:   fmt.Sprintf("fd://%d", fd),
		Proxy:    fmt.Sprintf("socks5://%s", socksAddr),
		LogLevel: logLevel,
		MTU:      1500,
	}
	engine.Insert(key)
	engine.Start()
	interceptDNS()
	started = true
	return nil
}

// interceptDNS routes DNS through the tunnel over TCP.
func interceptDNS() {
	// Движок уже создал прокси из ключа выше, и туннель хранит его глобально.
	// Подменяем его обёрткой: она пропускает всё как раньше и трогает только
	// UDP на порт 53, иначе запрос ушёл бы напрямую к резолверу оператора.
	t := tunnel.T()
	if t == nil {
		return
	}
	inner := t.Proxy()
	if inner == nil {
		return
	}
	if _, wrapped := inner.(*dnsOverTCPProxy); wrapped {
		return
	}
	t.SetProxy(&dnsOverTCPProxy{inner: inner})
}

// Stop halts packet forwarding.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if !started {
		return
	}
	engine.Stop()
	started = false
}

// IsRunning reports the current state for the UI indicator.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return started
}
