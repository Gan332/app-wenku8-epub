#!/usr/bin/env bash
# 仓库 Actions 缓存水位控制（AGENTS 5.2 的补充约定）。
#
# 背景：公开仓库的 Actions 缓存配额是每仓库 10 GB，而 gradle/actions/setup-gradle
# 会为每个变化的 Gradle 配置哈希各存一份 gradle-transforms 缓存——本仓库曾积累到
# 39 条 / 8.1 GB，占了全部配额的四分之三。
#
# 配额超限不会让构建失败：actions/cache 静默跳过保存，只在日志留一行 warning。
# 真正的后果是缓存悄悄失效、每次 CI 退化成冷构建，属于缓慢且难定位的退化。
#
# 本脚本按 GitHub 的实际计数口径计算：只有最近 7 天内被访问过的条目才计入配额，
# 老条目即使还在也不占额度，所以下面的 usage 是真实占用而不是列表总量。
#
# 触发条件是占用达到 MAX_MB 的 THRESHOLD_PCT，随后按 last_accessed_at 由旧到新删除，
# 直到降到 TARGET_PCT 以下。本次 job 刚 restore 过（10 分钟内访问）的条目会被跳过，
# 否则会把正在使用的缓存删掉，导致当前构建反而失去缓存。
#
# 用法（workflow 里已调用，也可本地手动跑）：
#   CACHE_MAX_MB=10240 CACHE_THRESHOLD_PCT=90 CACHE_TARGET_PCT=70 bash scripts/ci-trim-cache.sh
#   CACHE_ALWAYS_CLEAN=1 bash scripts/ci-trim-cache.sh   # 无视阈值，强制清到目标水位
#
# 需要 actions: write 权限（删缓存用），workflow 的 permissions 已相应放开。
set -euo pipefail

REPO="${CACHE_REPO:-${GITHUB_REPOSITORY:-}}"
GH="${GH:-gh}"

MAX_MB="${CACHE_MAX_MB:-10240}"
THRESHOLD_PCT="${CACHE_THRESHOLD_PCT:-90}"
TARGET_PCT="${CACHE_TARGET_PCT:-70}"
ALWAYS_CLEAN="${CACHE_ALWAYS_CLEAN:-0}"

# GitHub 只把最近 7 天内被访问过的条目计入配额。
ACTIVE_WINDOW_HOURS=168
# 本次运行刚 restore 的缓存仍在使用，删掉会让当前构建失去缓存。
IN_USE_MINUTES=10

if [ -z "$REPO" ]; then
  echo "::notice::未提供仓库（GITHUB_REPOSITORY/CACHE_REPO），跳过缓存清理"
  exit 0
fi

# 不用 mktemp：Git Bash 的精简环境里可能没有它，fallback 到 .cache-trim.<pid>。
if command -v mktemp >/dev/null 2>&1; then
  TMP="$(mktemp -d)"
else
  TMP="${TMPDIR:-.}/cache-trim.$$"
  mkdir -p "$TMP"
fi
trap 'rm -rf "$TMP"' EXIT

echo "==> 列出缓存（$REPO）"
"$GH" api --paginate "repos/$REPO/actions/caches?per_page=100" \
  --jq '.actions_caches[] | "\(.id)\t\(.key)\t\(.size_in_bytes)\t\(.last_accessed_at)"' \
  > "$TMP/caches.tsv"

TOTAL="$(wc -l < "$TMP/caches.tsv" | tr -d ' ')"
echo "    条目总数 = $TOTAL"

NOW="$(date -u +%s)"

# 逐条判断是否计入配额（7 天内访问过），累加真实占用。
# 同时输出可删除候选：够老、且不是本次运行刚 restore 的。
CUTOFF_ACTIVE=$(( NOW - ACTIVE_WINDOW_HOURS * 3600 ))
CUTOFF_IN_USE=$(( NOW - IN_USE_MINUTES * 60 ))
: > "$TMP/usage.tsv"
: > "$TMP/evictable.tsv"

while IFS=$'\t' read -r id key bytes accessed; do
  [ -z "${id:-}" ] && continue
  accessed_epoch="$(date -u -d "$accessed" +%s 2>/dev/null || echo 0)"
  if [ "$accessed_epoch" -ge "$CUTOFF_ACTIVE" ]; then
    echo "$bytes" >> "$TMP/usage.tsv"
    if [ "$accessed_epoch" -lt "$CUTOFF_IN_USE" ]; then
  printf '%s\t%s\t%s\t%s\n' "$accessed_epoch" "$id" "$key" "$bytes" >> "$TMP/evictable.tsv"
    fi
  fi
done < "$TMP/caches.tsv"

if [ ! -s "$TMP/usage.tsv" ]; then
  USAGE_MB=0
else
  USAGE_MB="$(awk '{s+=$1} END {printf "%d", s/1048576}' "$TMP/usage.tsv")"
fi
echo "    7 天内有效占用 = ${USAGE_MB} MB / ${MAX_MB} MB"

TRIGGER_MB=$(( MAX_MB * THRESHOLD_PCT / 100 ))
TARGET_MB=$(( MAX_MB * TARGET_PCT / 100 ))

if [ "$ALWAYS_CLEAN" != "1" ] && [ "$USAGE_MB" -lt "$TRIGGER_MB" ]; then
  echo "    未达 ${THRESHOLD_PCT}% 阈值（${TRIGGER_MB} MB），跳过清理"
  exit 0
fi

if [ ! -s "$TMP/evictable.tsv" ]; then
  echo "::warning::缓存占用 ${USAGE_MB} MB 已达阈值，但没有可删除的候选（其余条目 10 分钟内刚被访问）"
  exit 0
fi

echo "==> 超阈值，开始按最旧优先删除，目标降到 ${TARGET_MB} MB 以下"
DELETED=0
RECLAIMED_MB=0

# sort -n 按 accessed_epoch 升序：最久未访问的先删。
# 用进程替换而不是 `sort | while`���后者在子 shell 里跑，USAGE_MB 的累加不会回传父 shell，
# 末尾就会拿旧值误报「清理后仍超标」。
while IFS=$'\t' read -r accessed id key bytes; do
  if [ "$USAGE_MB" -le "$TARGET_MB" ]; then
    break
  fi
  mb=$(( bytes / 1048576 ))
  # 按 id 删而不是 ?key=：key 里含 `|`、空格等字符，拼进查询串要做 URL 编码，
  # 一旦编码不一致就静默删不掉。按 id 删是幂等的，已被并发删掉时返回 404 走 else 分支。
  if "$GH" api -X DELETE "repos/$REPO/actions/caches/$id" >/dev/null 2>&1; then
    DELETED=$(( DELETED + 1 ))
    RECLAIMED_MB=$(( RECLAIMED_MB + mb ))
    USAGE_MB=$(( USAGE_MB - mb ))
    echo "    - $key (-${mb} MB)"
  else
    echo "    ! 跳过（可能已被并发运行删除）: $key"
  fi
done < <(sort -n "$TMP/evictable.tsv")

echo "==> 已删除 $DELETED 条，回收约 ${RECLAIMED_MB} MB，当前占用约 ${USAGE_MB} MB"

if [ "$USAGE_MB" -gt "$TARGET_MB" ]; then
  echo "::warning::清理后仍为 ${USAGE_MB} MB（目标 ${TARGET_MB} MB）。剩余条目都是最近 10 分钟内访问的，等它们变旧后下次构建会再清一轮。"
fi
