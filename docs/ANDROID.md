# Android-клиент

Приложение с кнопкой включения, индикатором состояния и лентой лога. Весь трафик
устройства заворачивается в туннель через `VpnService` — настраивать прокси вручную
не нужно.

## Как устроено

```
VpnService (tun) → tun2socks → SOCKS5 (127.0.0.1:1080) → openflux --client → транспорт → exit node
```

* `OpenFluxVpnService` поднимает tun-интерфейс и запускает бинарь клиента;
* `mobile/` — Go-обёртка над [tun2socks](https://github.com/xjasonlyu/tun2socks),
  собирается в AAR и связывает дескриптор tun с локальным SOCKS5;
* сам бинарь клиента поставляется внутри APK как нативная библиотека.

## Сборка

Нужны Go 1.26.3+, Android NDK r27+, Android SDK 35, JDK 17, Gradle 8.7.

Всё сразу — бинарь, AAR и APK:

```bash
export ANDROID_NDK_HOME=/opt/android-ndk
export ANDROID_HOME=/opt/android-sdk
./build_apk.sh
# android/app/build/outputs/apk/debug/app-debug.apk
```

Пути к NDK и SDK берутся из переменных окружения (по умолчанию `/opt/android-ndk`
и `/opt/android-sdk`), команда Gradle — из `GRADLE`. Недостающий `gomobile`
скрипт доставит сам.

### Те же шаги вручную

```bash
# 1. бинарь клиента под arm64
export ANDROID_NDK_HOME=/opt/android-ndk
./build_android_linux.sh          # на macOS используйте build_android.sh

# 2. AAR с tun2socks
cd mobile
go mod tidy
gomobile bind -target=android/arm64 -androidapi 24 -o openfluxmobile.aar .

# 3. складываем артефакты в проект приложения
cd ..
mkdir -p android/app/libs android/app/src/main/jniLibs/arm64-v8a
cp mobile/openfluxmobile.aar android/app/libs/
cp output/android/arm64-v8a/openflux android/app/src/main/jniLibs/arm64-v8a/libopenflux.so

# 4. APK
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Подводные камни

**Бинарь обязан лежать в `jniLibs` под именем `libopenflux.so`.** Начиная с Android 10
исполнение файлов из каталога данных приложения запрещено (W^X), поэтому копирование
из `assets` в `filesDir` даёт `error=13, Permission denied` даже после `setExecutable`.
Исполняемым остаётся только `applicationInfo.nativeLibraryDir`. Дополнительно нужны
`android:extractNativeLibs="true"` в манифесте и
`packaging { jniLibs { useLegacyPackaging = true } }` в Gradle — иначе библиотека
останется сжатой внутри APK и файла на диске не появится.

**DNS идёт мимо туннеля.** SOCKS5-сервер в OpenFlux принимает только команду `CONNECT`
(`buf[1] != 0x01 → return`), то есть UDP не проксируется вовсе. А DNS работает по UDP,
и если завернуть его в туннель, домены перестают резолвиться — со стороны выглядит как
полностью пропавший интернет, хотя соединения по IP работают. Поэтому приложение строит
таблицу из 32 маршрутов, покрывающих `0.0.0.0/0` за вычетом адреса DNS-сервера.
`VpnService.Builder.excludeRoute()` для этого не годится — он доступен только с Android 13.
Цена решения: DNS-запросы видны провайдеру.

**Приложение исключается из собственного туннеля** через `addDisallowedApplication`,
иначе клиент не сможет достучаться до транспорта и получится петля. Дочерний процесс
наследует UID приложения, поэтому исключение действует и на бинарь.

**Не включайте `--debug` в клиенте, если пишете вывод в интерфейс.** Подробный режим
выдаёт построчный дамп каждого пакета — тысячи строк в секунду кладут UI и роняют
приложение. В коде дамп отфильтрован, а частота обновления ленты ограничена.
