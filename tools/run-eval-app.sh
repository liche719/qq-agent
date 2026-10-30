#!/bin/bash
cd /c/Users/33721/Desktop/wechat-agent/qq-agent
export SPRING_PROFILES_ACTIVE=local
export DB_URL='jdbc:postgresql://localhost:55433/wv_rescue'
export DB_DRIVER='org.postgresql.Driver'
export MYSQL_USER=wv
export MYSQL_PASSWORD=wv
export REDIS_HOST=localhost
export REDIS_PORT=56379
export SPRING_QUARTZ_JDBC_INITIALIZE_SCHEMA=always
export QUARTZ_DELEGATE='org.quartz.impl.jdbcjobstore.PostgreSQLDelegate'
export AGENT_CHANNEL_MODE=simulator
export QQ_ENABLED=false
export MEMORY_SELF_ENABLED=false
export SCHEDULED_ENABLED=false
export SERVER_PORT=18080
export SERVER_SSL_ENABLED=false
export AGENT_EVAL_ENABLED=true
export MEMORY_RECALL_KEYWORD_FALLBACK=true
export ADMIN_REQUIRE_KEY=false
exec java -jar target/qq-agent-0.0.1-SNAPSHOT.jar
