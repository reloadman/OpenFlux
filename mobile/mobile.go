// Package openfluxmobile bridges Android VpnService and the tunnel.
package openfluxmobile

import (
	"fmt"
	"sync"

	"github.com/xjasonlyu/tun2socks/v2/engine"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"golang.org/x/sys/unix"
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

	// Дублируем дескриптор: при остановке tun2socks делает unix.Close() на том
	// номере, что ему передали. Если это был бы дескриптор из ParcelFileDescriptor,
	// то Kotlin закрыл бы его повторно — а к этому моменту номер могли переиспользовать,
	// и приложение падало бы нативно, мимо try/catch. Теперь каждая сторона
	// закрывает собственный дескриптор.
	dup, err := unix.Dup(fd)
	if err != nil {
		return fmt.Errorf("dup tun fd: %w", err)
	}

	resetCounters()
	key := &engine.Key{
		Device:   fmt.Sprintf("fd://%d", dup),
		Proxy:    fmt.Sprintf("socks5://%s", socksAddr),
		LogLevel: logLevel,
		MTU:      1500,
	}
	engine.Insert(key)
	engine.Start()
	wrapProxy()
	started = true
	return nil
}

// wrapProxy adds DNS interception and byte counting.
func wrapProxy() {
	// Движок уже создал прокси из ключа выше, и туннель хранит его глобально.
	// Счётчик кладём под перехватчик DNS, чтобы запросы к резолверу тоже попадали
	// в статистику.
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
	t.SetProxy(&dnsOverTCPProxy{inner: &countingProxy{inner: inner}})
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
