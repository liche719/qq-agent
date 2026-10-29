#!/bin/bash
# 用文件传 SQL，避免多层引号/转义出错
ssh root@120.25.170.92 "docker exec -i qq-agent-postgres psql -U wechat_app -d wechat_agent -t -A -F '|' -f /dev/stdin" < "$1"
