#!/bin/bash
# 在服务器上查询 agent_run / agent_step（避免 psql 引号转义问题）
ssh root@120.25.170.92 "docker exec codepilot-postgres psql -U codepilot -d codepilot -t -A -F '|' -c \"$1\""
