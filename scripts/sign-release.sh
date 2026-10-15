#!/usr/bin/env bash
set -euo pipefail
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

if [[ $# -ne 2 ]]; then
    printf 'Usage: bash scripts/sign-release.sh unsigned.apk output.apk\n' >&2
    exit 2
fi
INPUT="$1"
OUTPUT="$2"
SIGNING_DIR="${CARADB_SIGNING_DIR:-$CARADB_TOOLS/signing}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"
AAPT="$BUILD_TOOLS/aapt"

if [[ ! -f "$INPUT" || -d "$OUTPUT" ]]; then
    printf 'Expected an existing input APK and a file output path.\n' >&2
    exit 1
fi
SIGNING_MODE="${CARADB_SIGNING_MODE:-auto}"
case "$SIGNING_MODE" in
    auto)
        SIGNING_MODE=single
        # Any rotation material, including a broken symlink, requires the full identity.
        for asset in legacy.keystore legacy.pass caradb.lineage; do
            if [[ -e "$SIGNING_DIR/$asset" || -L "$SIGNING_DIR/$asset" ]]; then
                SIGNING_MODE=rotation
                break
            fi
        done
        ;;
    single|rotation) ;;
    *)
        printf 'Invalid CARADB_SIGNING_MODE: %s (expected auto, single, or rotation).\n' "$SIGNING_MODE" >&2
        exit 2
        ;;
esac

assets=(release.p12 release.pass)
if [[ "$SIGNING_MODE" == "rotation" ]]; then
    assets=(legacy.keystore legacy.pass release.p12 release.pass caradb.lineage)
fi
for asset in "${assets[@]}"; do
    path="$SIGNING_DIR/$asset"
    if [[ ! -f "$path" || ! -s "$path" || ! -r "$path" ]]; then
        printf 'Missing signing material: %s. Restore the existing identity; do not generate a replacement.\n' "$path" >&2
        exit 1
    fi
    permissions="$(stat -c '%a' "$path")"
    if (( (8#$permissions & 077) != 0 )); then
        printf 'Signing material must not be accessible to group/others: %s (use mode 0600).\n' "$path" >&2
        exit 1
    fi
done

badging="$("$AAPT" dump badging "$INPUT")"
if ! grep -q "^package: name='dev.caradb' " <<< "$badging"; then
    printf 'Refusing to sign an APK for another package.\n' >&2
    exit 1
fi
if grep -q '^application-debuggable' <<< "$badging"; then
    printf 'Refusing to publish a debuggable APK as release.\n' >&2
    exit 1
fi
manifest="$("$AAPT" dump xmltree "$INPUT" AndroidManifest.xml)"
if grep -Eq 'android:testOnly.*0xffffffff' <<< "$manifest"; then
    printf 'Refusing to publish a test-only APK as release.\n' >&2
    exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"
work="$(mktemp -d "$(dirname "$OUTPUT")/.release-sign.XXXXXX")"
trap 'rm -rf "$work"' EXIT
"$ZIPALIGN" -f -P 16 4 "$INPUT" "$work/aligned.apk"
# Each key uses its store password; reusing a password file twice consumes two lines.
signer_args=()
if [[ "$SIGNING_MODE" == "rotation" ]]; then
    signer_args+=(
        --rotation-min-sdk-version 28
        --lineage "$SIGNING_DIR/caradb.lineage"
        --ks "$SIGNING_DIR/legacy.keystore"
        --ks-key-alias "${CARADB_LEGACY_KEY_ALIAS:-androiddebugkey}"
        --ks-pass "file:$SIGNING_DIR/legacy.pass"
        --next-signer
    )
fi
signer_args+=(
    --ks "$SIGNING_DIR/release.p12"
    --ks-key-alias "${CARADB_RELEASE_KEY_ALIAS:-caradb-release}"
    --ks-pass "file:$SIGNING_DIR/release.pass"
)
"$APKSIGNER" sign \
    --in "$work/aligned.apk" \
    --out "$work/signed.apk" \
    --min-sdk-version 23 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --v4-signing-enabled false \
    --debuggable-apk-permitted false \
    "${signer_args[@]}"
"$APKSIGNER" verify --verbose "$work/signed.apk"
"$APKSIGNER" verify --min-sdk-version 23 --max-sdk-version 27 "$work/signed.apk"
"$APKSIGNER" verify --min-sdk-version 28 --max-sdk-version 30 "$work/signed.apk"
"$ZIPALIGN" -c -P 16 4 "$work/signed.apk"
chmod 0644 "$work/signed.apk"
mv -f "$work/signed.apk" "$OUTPUT"
printf '\nRelease APK: %s\n' "$OUTPUT"
