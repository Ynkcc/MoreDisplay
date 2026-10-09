#!/usr/bin/env bash
# 把模块元数据与已签名 APK 推到 LSPosed 官方收录仓库 Xposed-Modules-Repo/<package>。
#
# 用法：
#   ./scripts/publish-lsposed.sh <tag> <title> <notes-file> <apk> [<apk>...]
#   # tag 必须是 `<versionCode>-<versionName>`，例如 1-1.0
#
# 需要的环境：
#   GH_TOKEN        具备 Xposed-Modules-Repo/<package> 写权限的 PAT（CI 里用 secret 注入）
#   LSPOSED_REPO    可选，默认 Xposed-Modules-Repo/io.github.ynkcc.moredisplay
set -euo pipefail

cd "$(dirname "$0")/.."

TAG="${1:?usage: publish-lsposed.sh <tag> <title> <notes-file> <apk>...}"
TITLE="${2:?missing title}"
NOTES_FILE="${3:?missing notes file}"
shift 3
APKS=("$@")
[[ ${#APKS[@]} -gt 0 ]] || { echo "missing apk" >&2; exit 1; }

REPO="${LSPOSED_REPO:-Xposed-Modules-Repo/io.github.ynkcc.moredisplay}"
: "${GH_TOKEN:?GH_TOKEN is not set}"

# 写入（或更新）仓库里的元数据文件。LSPosed 用 SUMMARY 做首页摘要、README.md 做详情页。
put_file() {
    local path="$1" src="$2"
    local sha content
    sha="$(gh api "repos/$REPO/contents/$path" --jq .sha 2>/dev/null || true)"
    content="$(base64 -w0 <"$src")"
    if [[ -n "$sha" ]]; then
        gh api -X PUT "repos/$REPO/contents/$path" \
            -f message="Update $path for $TITLE" \
            -f content="$content" -f sha="$sha" >/dev/null
        echo "updated $path"
    else
        gh api -X PUT "repos/$REPO/contents/$path" \
            -f message="Add $path for $TITLE" \
            -f content="$content" >/dev/null
        echo "created $path"
    fi
}

put_file SUMMARY dist/SUMMARY
put_file README.md dist/README.md

# 收录仓库靠 release + 附带的 apk 出包；tag 必须是 <versionCode>-<versionName>。
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    gh release upload "$TAG" --repo "$REPO" --clobber "${APKS[@]}"
    echo "uploaded to existing release $TAG"
else
    gh release create "$TAG" --repo "$REPO" --title "$TITLE" \
        --notes-file "$NOTES_FILE" "${APKS[@]}"
    echo "created release $TAG"
fi

echo "published $TAG -> https://github.com/$REPO/releases/tag/$TAG"
