#!/usr/bin/env bash
# 本地构建已签名的 release APK。
#
# 用法：
#   ./scripts/build-release.sh                     # 只构建 :app
#   ./scripts/build-release.sh --all               # 同时构建 :probe（验收用探测程序）
#   ANDROID_SIGNING_ENV=/path/to/env ./scripts/build-release.sh
#
# 签名信息来自一个 shell 片段文件，默认 ~/.config/zsh/secret/android-signing.zsh，
# 该文件只 export 四个变量，不入库：
#   RELEASE_STORE_PASSWORD / RELEASE_KEY_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEYSTORE_BASE64
set -euo pipefail

cd "$(dirname "$0")/.."

SIGNING_ENV="${ANDROID_SIGNING_ENV:-$HOME/.config/zsh/secret/android-signing.zsh}"
if [[ -f "$SIGNING_ENV" ]]; then
    # shellcheck disable=SC1090
    source "$SIGNING_ENV"
    export RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEYSTORE_BASE64
    echo "signing: loaded $SIGNING_ENV"
else
    echo "signing: $SIGNING_ENV not found -> release APK will be UNSIGNED" >&2
fi

TASKS=(:app:assembleRelease)
if [[ "${1:-}" == "--all" ]]; then
    TASKS+=(:probe:assembleRelease)
fi

exec ./gradlew "${TASKS[@]}"
