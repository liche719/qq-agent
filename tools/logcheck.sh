#!/usr/bin/env bash
# 日志速查（只读）。用法：bash tools/logcheck.sh [小时数]
#
# 为什么有它：容器重建后 `docker logs` 就空了，真正的日志在宿主机
# /opt/qq-agent/logs/。这个脚本把最常用的几种看法收在一起。

set -uo pipefail

HOURS="${1:-2}"
ROOT="${LOG_ROOT:-/opt/qq-agent/logs}"
SINCE=$(date -d "-${HOURS} hours" '+%Y-%m-%d %H:%M' 2>/dev/null || date '+%Y-%m-%d %H:%M')

if [ ! -d "$ROOT" ]; then
  echo "找不到日志目录：$ROOT" >&2
  exit 1
fi

echo "== 最近 ${HOURS} 小时（自 ${SINCE}）"
echo

echo "-- ERROR / WARN 汇总（按 logger 计数）"
grep -h " ${SINCE#*-}" /dev/null 2>/dev/null || true
awk -v since="$SINCE" '
  $0 ~ /^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}/ {
    ts = substr($0, 1, 16)
    if (ts >= since && ($0 ~ / ERROR / || $0 ~ / WARN /)) print
  }' "$ROOT"/system/agent.log "$ROOT"/user/*/agent.log 2>/dev/null \
  | sed -E 's/^[0-9-]+ [0-9:.]+ +//; s/ - .*//' \
  | sort | uniq -c | sort -rn | head -12

echo
echo "-- 最近的 ERROR 原文（最多 15 条）"
awk -v since="$SINCE" '
  $0 ~ /^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}/ {
    ts = substr($0, 1, 16); cur = ($0 ~ / ERROR /)
  }
  { if (cur && ts >= since) print }' "$ROOT"/system/agent.log 2>/dev/null | tail -15

echo
echo "-- 工具失败（最近 30 条）"
grep -h "工具完成 name=" "$ROOT"/system/agent.log "$ROOT"/user/*/agent.log 2>/dev/null \
  | grep -v "success=true" | tail -30

echo
echo "-- 慢调用（模型 > 20 秒）"
grep -h "LLM 调用 scenario=" "$ROOT"/system/agent.log "$ROOT"/user/*/agent.log 2>/dev/null \
  | awk -F'ms=' '{split($2, a, " "); if (a[1] + 0 > 20000) print}' | tail -15

echo
echo "提示：完整日志在 $ROOT/system/agent.log（30 天，压缩归档同目录）"
