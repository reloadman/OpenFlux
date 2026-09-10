#!/bin/bash
# Изолированное сетевое окружение для OpenFlux.
# Правило "DROP исходящих TCP RST" нужно самому туннелю: он работает через
# raw-сокеты, и ядро иначе рвёт его соединения своими RST. Глобально такое
# правило подвесило бы sing-box, через который идёт вся домашняя сеть, поэтому
# оно живёт только здесь, внутри namespace.
set -e
NS=openflux
UPLINK=$(ip route show default | awk '{print $5; exit}')

ip netns list | grep -qw "$NS" || ip netns add "$NS"

ip link del ofx-host 2>/dev/null || true
ip link add ofx-host type veth peer name ofx-ns
ip link set ofx-ns netns "$NS"
ip addr add 10.200.0.1/30 dev ofx-host
ip link set ofx-host up

ip netns exec "$NS" ip addr add 10.200.0.2/30 dev ofx-ns
ip netns exec "$NS" ip link set ofx-ns up
ip netns exec "$NS" ip link set lo up
ip netns exec "$NS" ip route add default via 10.200.0.1

sysctl -qw net.ipv4.ip_forward=1
iptables -t nat -C POSTROUTING -s 10.200.0.0/30 -o "$UPLINK" -j MASQUERADE 2>/dev/null \
    || iptables -t nat -A POSTROUTING -s 10.200.0.0/30 -o "$UPLINK" -j MASQUERADE
iptables -C FORWARD -i ofx-host -o "$UPLINK" -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 1 -i ofx-host -o "$UPLINK" -j ACCEPT
iptables -C FORWARD -i "$UPLINK" -o ofx-host -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null \
    || iptables -I FORWARD 2 -i "$UPLINK" -o ofx-host -m state --state RELATED,ESTABLISHED -j ACCEPT

ip netns exec "$NS" iptables -C OUTPUT -p tcp --tcp-flags RST RST -j DROP 2>/dev/null \
    || ip netns exec "$NS" iptables -A OUTPUT -p tcp --tcp-flags RST RST -j DROP

mkdir -p /etc/netns/"$NS"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/netns/"$NS"/resolv.conf
echo "namespace $NS готов (uplink $UPLINK)"
