#!/usr/bin/env bash
# 通过 GitHub Git Database API 推送（本项目约定：本机 git push 在 Windows Schannel 下不可靠）。
#
# 背景：远端 main 的历史是由本脚本创建的，**提交 SHA 与本地不同**
# （且部分文件的换行被规范化为 CRLF），因此不能用 `git merge-base` 判祖先。
# 本脚本的做法是：
#   1. 取远端 main 的 commit / tree；
#   2. 用 `git diff --name-only <local-base> HEAD` 算出「本次真正改动的文件」
#      （local-base 是内容与远端等价的本地提交）；
#   3. 把这些文件作为 blob 挂到**远端 tree** 上，创建新 commit，PATCH ref。
# 这样远端换行差异不会被本地内容回冲。
#
# 踩过的坑：
#   1. 用 bash 直接调用 gh.exe（Node spawnSync 会因安全软件锁文件报 EBUSY）。
#   2. 进程替换 <(...) 在此 Git Bash 下不可用，payload 一律先写临时文件再 --input。
#   3. gh api 响应偶发为空，此时先查远端 SHA，已更新则视为成功，避免重复提交。
set -euo pipefail

REPO="Gan332/app-wenku8-epub"
GH="D:/SW/gh/gh.exe"
BRANCH="main"
PY="C:/Users/fish/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"
# 本地「内容等价于远端 main」的提交：本次改动的 diff 基准
LOCAL_BASE="${LOCAL_BASE:-9bce89f}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

trim() { tr -d '\r\n' < "$1"; }

echo "==> 远端 $BRANCH 当前 SHA"
"$GH" api "repos/$REPO/git/ref/heads/$BRANCH" --jq '.object.sha' > "$TMP/parent"
PARENT="$(trim "$TMP/parent")"
echo "    parent     = $PARENT"

"$GH" api "repos/$REPO/git/commits/$PARENT" --jq '.tree.sha' > "$TMP/basetree"
BASETREE="$(trim "$TMP/basetree")"
echo "    base_tree  = $BASETREE  (远端 tree，作为新 tree 的基底)"

LOCAL_SHA="$(git rev-parse HEAD)"
echo "    local HEAD = $LOCAL_SHA"
echo "    diff base  = $LOCAL_BASE"

git diff --name-only --diff-filter=d "$LOCAL_BASE" "$LOCAL_SHA" > "$TMP/files"
echo "==> 本次改动文件"
sed 's/^/    /' "$TMP/files"

if [ ! -s "$TMP/files" ]; then
  echo "==> 没有改动，退出"
  exit 0
fi

: > "$TMP/entries.jsonl"
while IFS= read -r f; do
  [ -z "$f" ] && continue
  [ -f "$f" ] || { echo "    skip (本地不存在): $f"; continue; }

  "$PY" - "$f" "$TMP/blob_payload.json" <<'PY'
import base64, json, sys
raw = open(sys.argv[1], "rb").read()
json.dump({"content": base64.b64encode(raw).decode("ascii"), "encoding": "base64"},
          open(sys.argv[2], "w", encoding="utf-8"))
PY

  "$GH" api "repos/$REPO/git/blobs" --input "$TMP/blob_payload.json" --jq '.sha' > "$TMP/blob_sha"
  sha="$(trim "$TMP/blob_sha")"
  mode="100644"
  case "$f" in *.sh) mode="100755" ;; esac
  "$PY" - "$TMP/entries.jsonl" "$f" "$mode" "$sha" <<'PY'
import json, sys
entry = {"path": sys.argv[2], "mode": sys.argv[3], "type": "blob", "sha": sys.argv[4]}
open(sys.argv[1], "a", encoding="utf-8").write(json.dumps(entry) + "\n")
PY
  echo "    blob $sha  $f"
done < "$TMP/files"

"$PY" - "$TMP/entries.jsonl" "$BASETREE" "$TMP/tree_payload.json" <<'PY'
import json, sys
entries = [json.loads(l) for l in open(sys.argv[1], encoding="utf-8") if l.strip()]
json.dump({"base_tree": sys.argv[2], "tree": entries}, open(sys.argv[3], "w", encoding="utf-8"))
print(f"    entries = {len(entries)}")
PY

echo "==> 创建 tree"
"$GH" api "repos/$REPO/git/trees" --input "$TMP/tree_payload.json" --jq '.sha' > "$TMP/newtree"
NEWTREE="$(trim "$TMP/newtree")"
echo "    tree   = $NEWTREE"

git log -1 --pretty=%B HEAD > "$TMP/msg"
"$PY" - "$TMP/msg" "$NEWTREE" "$PARENT" "$TMP/commit_payload.json" <<'PY'
import json, sys
msg = open(sys.argv[1], encoding="utf-8").read().strip()
json.dump({"message": msg, "tree": sys.argv[2], "parents": [sys.argv[3]]},
          open(sys.argv[4], "w", encoding="utf-8"))
PY

echo "==> 创建 commit"
"$GH" api "repos/$REPO/git/commits" --input "$TMP/commit_payload.json" --jq '.sha' > "$TMP/newcommit"
NEWCOMMIT="$(trim "$TMP/newcommit")"
echo "    commit = $NEWCOMMIT"

"$PY" - "$NEWCOMMIT" "$TMP/ref_payload.json" <<'PY'
import json, sys
json.dump({"sha": sys.argv[1], "force": False}, open(sys.argv[2], "w", encoding="utf-8"))
PY

echo "==> 更新 ref/heads/$BRANCH"
"$GH" api -X PATCH "repos/$REPO/git/refs/heads/$BRANCH" --input "$TMP/ref_payload.json" --jq '.object.sha' > "$TMP/ref_out"
echo "    远端现为 = $(trim "$TMP/ref_out")"
echo "==> 推送完成"
