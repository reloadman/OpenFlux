# Развёртывание exit node

## Зачем нужна изоляция

Штатная инструкция требует правила:

```bash
iptables -A OUTPUT -p tcp --tcp-flags RST RST -j DROP
```

Оно необходимо самому туннелю: exit node работает через raw-сокеты, и ядро иначе рвёт
его соединения собственными RST. Но правило **глобальное** — оно запрещает машине
отправлять RST вообще. На сервере, где рядом живут другие сетевые службы (прокси, веб,
контейнеры), это подвешивает их соединения.

Решение — запускать exit node в отдельном network namespace. Правило действует только
внутри него, остальная система не затронута.

## Установка

```bash
# бинарь
git clone <этот-форк> /opt/openflux && cd /opt/openflux
go mod tidy && go build -o universal-bypass-tool .

# изолированное окружение
sudo install -m 755 deploy/ofx-netns.sh /usr/local/sbin/
sudo install -m 644 deploy/openflux-netns.service deploy/openflux.service /etc/systemd/system/
sudo install -m 640 deploy/openflux.env.example /etc/openflux.env

# указать URL документа
sudo nano /etc/openflux.env

sudo systemctl daemon-reload
sudo systemctl enable --now openflux-netns openflux
```

`ofx-netns.sh` создаёт namespace `openflux`, пару veth (10.200.0.1 ↔ 10.200.0.2),
настраивает NAT наружу и применяет правило DROP RST **только внутри namespace**.
Подсеть при необходимости поправьте в скрипте, если 10.200.0.0/30 занята.

## Проверка

```bash
# правило действует внутри namespace
sudo ip netns exec openflux iptables -L OUTPUT -n     # DROP tcp flags:0x04/0x04
# и НЕ действует на хосте
sudo iptables -L OUTPUT -n                            # пусто

# exit node подключился к транспорту
sudo ip netns exec openflux ss -tnp                   # ESTAB к 87.250.x.x:443

# идут ли данные от клиента
sudo journalctl -u openflux -f
```

В логе строки вида `<- 60 bytes - TCP 10.10.10.2:... [SYN]` означают, что пакеты от
клиента доходят и туннель работает.

## Ссылка на документ

Транспорт `yandex` читает из HTML страницы блок `client-config` и берёт оттуда
`officeActionData.balancer_url` и `editor_config.document.key`. Яндекс отдаёт эту
структуру **не для всех ссылок**:

| Вид ссылки | Подходит |
|---|---|
| `disk.360.yandex.ru/i/<id>` — «поделиться файлом» | да |
| `disk.360.yandex.ru/edit/d/<id>` — редактор | нет, отдаёт новый WOPI-редактор |

Параметры `?editor=legacy`, `?force_legacy=1`, `?source=docs` ситуацию не меняют.
Проверить ссылку можно одной командой — должно вернуть число больше нуля:

```bash
curl -sL -H 'User-Agent: Mozilla/5.0' 'ССЫЛКА' | grep -c balancer_url
```

Без заголовка `User-Agent` Яндекс отвечает капчей. В правах документа должно стоять
разрешение на редактирование: транспорт пишет данные в позиции курсора.

## Транспорт обязан ходить мимо VPN

Если exit node выпускается через VPN (как в схеме выше — трафик namespace заворачивается
в sing-box), то через VPN пойдёт и обращение **самого транспорта** к Яндекс.Документам.
Яндекс зарубежным адресам не отвечает вовсе: TLS-handshake молча истекает по таймауту.

Выглядит это обманчиво — туннель поднят с обеих сторон, приложение показывает «работает»,
но не передаётся ни байта:

```
[YDOCS] fetchDocInfo failed: ... net/http: TLS handshake timeout
[YDOCS] Keep-alive failed: write tcp ...->87.250.251.212:443: write: broken pipe
[STATS] uptime=77h12m0s packets=0 connected=0 established=0 retrans=0
```

Диагностика в две команды — через прокси таймаут, напрямую мгновенный ответ:

```bash
curl --socks5-hostname 127.0.0.1:1080 https://disk.360.yandex.ru/i/<id>   # виснет
curl https://disk.360.yandex.ru/i/<id>                                     # HTTP 302
```

Лечится правилом в `route.rules` конфига sing-box, поставленным **перед** правилом, которое
заворачивает всё в proxy:

```json
{"domain_suffix": ["yandex.ru", "yandex.net", "yandex.com", "ya.ru", "yandexcloud.net"],
 "outbound": "direct"}
```

Прятать транспорт за VPN и не требуется: наружу через туннель выходит уже трафик клиента.
Если конфиг генерируется скриптом, правило нужно закрепить в самом генераторе — иначе
следующий прогон его сотрёт.

## Замечание про смысл размещения

Exit node выпускает трафик со своего адреса. Если он стоит в той же стране, где
применяются ограничения, обхода геоблокировок это не даёт — но остаётся полезным
сценарием «дотянуться до домашней сети и её маршрутизации извне».
