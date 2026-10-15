#!/usr/bin/env bash
# Source this file before invoking the Android build tools (Linux/GNU).
export CARADB_TOOLS="${CARADB_TOOLS:-$HOME/.local/share/caradb}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$CARADB_TOOLS/android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
export GRADLE_HOME="${GRADLE_HOME:-$CARADB_TOOLS/gradle-8.11.1}"
if [[ -z "${JAVA_HOME:-}" ]] && command -v javac >/dev/null; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    export JAVA_HOME
fi
export PATH="$CARADB_TOOLS/bin:$GRADLE_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:${JAVA_HOME:-/usr}/bin:$PATH"
