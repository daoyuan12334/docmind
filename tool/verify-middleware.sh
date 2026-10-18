#!/usr/bin/env bash
# =============================================================================
# 真中间件联调：Redis + Kafka 全部对着真实实例跑，不靠 mock
#
#   ./tool/verify-middleware.sh          # 跑 RedisCacheIT + KafkaMessageBusIT
#   ./tool/verify-middleware.sh redis    # 只跑 Redis
#   ./tool/verify-middleware.sh kafka    # 只跑 Kafka
#
# 为什么需要它：日常 `mvn test` 只收 *Test，这两个 *IT 不会自动跑，
# 于是「Redis / Kafka 真的通不通」很容易长期处于「没验证过」的状态。
# 这个脚本把起服务和跑测试串起来，一条命令得出可复现的结论。
#
# Kafka 用 spring-kafka-test 的 @EmbeddedKafka（KRaft 模式），不需要 Docker；
# Redis 需要本机有一个实例，环境变量可用 REDIS_HOST / REDIS_PORT 覆盖。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${1:-all}"

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_CLI="${REDIS_CLI:-$HOME/.workbuddy/binaries/redis/redis-cli.exe}"
REDIS_SERVER="${REDIS_SERVER:-$HOME/.workbuddy/binaries/redis/redis-server.exe}"

export REDIS_HOST REDIS_PORT

redis_up() {
  if command -v "$REDIS_CLI" >/dev/null 2>&1; then
    "$REDIS_CLI" -h "$REDIS_HOST" -p "$REDIS_PORT" ping >/dev/null 2>&1
  else
    # 没有 redis-cli 时退而求其次：用 bash 直接连端口
    (exec 3<>"/dev/tcp/${REDIS_HOST}/${REDIS_PORT}") >/dev/null 2>&1
  fi
}

ensure_redis() {
  if redis_up; then
    echo "Redis 已在 ${REDIS_HOST}:${REDIS_PORT} 就绪"
    return
  fi
  if [[ -x "$REDIS_SERVER" ]]; then
    echo "启动 Redis（${REDIS_HOST}:${REDIS_PORT}）..."
    "$REDIS_SERVER" --port "$REDIS_PORT" --daemonize no >/tmp/docmind-redis.log 2>&1 &
    for _ in $(seq 1 20); do
      sleep 1
      if redis_up; then
        echo "Redis 已就绪"
        return
      fi
    done
  fi
  echo "跳过：连不上 Redis（${REDIS_HOST}:${REDIS_PORT}）。" >&2
  echo "  可先起一个实例（Windows 可 npm i -g redis-memory-server / 用 WSL / Docker：）" >&2
  echo "  docker run -d --name docmind-redis -p 6379:6379 redis:7-alpine" >&2
  return 1
}

run_redis_it() {
  if ! redis_up; then
    echo "== RedisCacheIT：跳过（Redis 未就绪）=="
    return 0
  fi
  echo "== RedisCacheIT =="
  (cd "$ROOT/backend" && "$ROOT/tool/mvn.sh" -B -ntp test -Dtest=RedisCacheIT -DfailIfNoTests=false)
}

run_kafka_it() {
  echo "== KafkaMessageBusIT（内嵌 broker，约 1~2 分钟）=="
  (cd "$ROOT/backend" && "$ROOT/tool/mvn.sh" -B -ntp test -Dtest=KafkaMessageBusIT -DfailIfNoTests=false)
}

case "$TARGET" in
  redis) ensure_redis || exit 0; run_redis_it ;;
  kafka) run_kafka_it ;;
  all)   ensure_redis || true; run_redis_it; run_kafka_it ;;
  *) echo "用法: $0 [all|redis|kafka]" >&2; exit 2 ;;
esac

echo
echo "中间件联调完成。"
