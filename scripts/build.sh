#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

VARIANT="${1:-debug}"
if [[ $# -gt 1 || ( "$VARIANT" != "debug" && "$VARIANT" != "release" ) ]]; then
    printf 'Usage: bash scripts/build.sh [debug|release]\n' >&2
    exit 2
fi

case "${CARADB_SIGNING_MODE:-auto}" in
    auto|single|rotation) ;;
    *)
        printf 'Invalid CARADB_SIGNING_MODE: %s (expected auto, single, or rotation).\n' "$CARADB_SIGNING_MODE" >&2
        exit 2
        ;;
esac

for tool in go java; do
    if ! command -v "$tool" >/dev/null; then
        printf 'Missing build tool: %s\n' "$tool" >&2
        exit 1
    fi
done
if [[ ! -f "$ANDROID_HOME/platforms/android-35/android.jar" || ! -d "$ANDROID_NDK_HOME/toolchains/llvm" ]]; then
    printf 'Android API 35 and NDK 27.2.12479018 are required. See README.md.\n' >&2
    exit 1
fi

MOBILE_VERSION="v0.0.0-20250520180527-a1d90793fc63"
mkdir -p "$CARADB_TOOLS/bin" "$ROOT/android/app/libs" "$ROOT/dist"
for tool in gomobile gobind; do
    if [[ ! -x "$CARADB_TOOLS/bin/$tool" ]]; then
        GOBIN="$CARADB_TOOLS/bin" go install "golang.org/x/mobile/cmd/$tool@$MOBILE_VERSION"
    fi
done

cd "$ROOT/core"
gomobile bind \
    -target=android/arm64,android/arm \
    -androidapi=23 \
    -javapkg=dev.caradb.bridge \
    -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384' \
    -o "$ROOT/android/app/libs/caradb.aar" \
    ./mobile

if [[ "$VARIANT" == "release" ]]; then
    "$ROOT/android/gradlew" --no-daemon -p "$ROOT/android" :app:assembleRelease
    bash "$ROOT/scripts/sign-release.sh" \
        "$ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk" \
        "$ROOT/dist/caradb-release.apk"
else
    "$ROOT/android/gradlew" --no-daemon -p "$ROOT/android" :app:assembleDebug
    cp "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" "$ROOT/dist/caradb-debug.apk"
    printf '\nAPK: %s\n' "$ROOT/dist/caradb-debug.apk"
fi
