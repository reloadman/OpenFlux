#!/bin/bash
# Полная сборка Android-клиента: бинарь туннеля -> AAR с tun2socks -> APK.
#
# Требуется: Go 1.26.3+, Android NDK r27+, Android SDK (build-tools + platform 35),
# JDK 17, Gradle 8.7+, gomobile.
set -e

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/android-ndk}"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
GRADLE="${GRADLE:-gradle}"
ABI=arm64-v8a          # имя каталога библиотек в APK
GOARCH_MOBILE=arm64    # как ту же архитектуру называет gomobile

say() { printf '\n==> %s\n' "$1"; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "не найдено: $1"; exit 1; }; }

need go
need "$GRADLE"
[ -d "$ANDROID_NDK_HOME" ] || { echo "нет NDK: $ANDROID_NDK_HOME"; exit 1; }
[ -d "$ANDROID_HOME" ]     || { echo "нет SDK: $ANDROID_HOME"; exit 1; }

export ANDROID_NDK_HOME ANDROID_HOME
export PATH="$PATH:$(go env GOPATH)/bin"

say "1/4 бинарь клиента ($ABI)"
case "$(uname -s)" in
    Darwin) ./build_android.sh ;;
    *)      ./build_android_linux.sh ;;
esac

say "2/4 AAR с tun2socks"
command -v gomobile >/dev/null 2>&1 || {
    echo "ставлю gomobile..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
    gomobile init
}
(
    cd mobile
    go mod tidy
    # tidy выбрасывает x/mobile: в коде он напрямую не используется, а gomobile
    # bind без него не работает — возвращаем зависимость после каждого tidy
    go get golang.org/x/mobile/bind
    # doc-комментарии Go попадают в генерируемую Java, которую javac собирает
    # в US-ASCII — держите их без кириллицы, иначе сборка развалится
    gomobile bind -target=android/"$GOARCH_MOBILE" -androidapi 24 -o openfluxmobile.aar .
)

say "3/4 раскладываю артефакты"
mkdir -p android/app/libs "android/app/src/main/jniLibs/$ABI"
cp mobile/openfluxmobile.aar android/app/libs/
# бинарь обязан называться lib*.so и лежать в jniLibs: Android 10+ разрешает
# исполнять файлы только из каталога нативных библиотек
cp "output/android/$ABI/openflux" "android/app/src/main/jniLibs/$ABI/libopenflux.so"

say "4/4 APK"
(
    cd android
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    "$GRADLE" assembleDebug --no-daemon
)

APK=android/app/build/outputs/apk/debug/app-debug.apk
if [ -f "$APK" ]; then
    printf '\nготово: %s (%s)\n' "$APK" "$(du -h "$APK" | cut -f1)"
else
    echo "APK не собрался"; exit 1
fi
