#!/bin/bash

# Redis Benchmark 테스트 스크립트
# Redis 서버의 성능을 측정합니다.

set -e

REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_URL="${REDIS_HOST}:${REDIS_PORT}"

echo "=========================================="
echo "Redis Benchmark 테스트 시작"
echo "=========================================="
echo "Redis 서버: ${REDIS_URL}"
echo ""

# Redis 연결 확인
echo "Redis 연결 확인 중..."
if ! redis-cli -h ${REDIS_HOST} -p ${REDIS_PORT} PING > /dev/null 2>&1; then
    echo "❌ Redis 서버에 연결할 수 없습니다."
    echo "   Docker Compose로 Redis를 시작하세요: docker-compose -f docker/infra-compose.yml up -d redis-master"
    exit 1
fi
echo "✅ Redis 연결 성공"
echo ""

# 기본 벤치마크 (GET/SET)
echo "=========================================="
echo "1. 기본 벤치마크 (GET/SET)"
echo "=========================================="
redis-benchmark -h ${REDIS_HOST} -p ${REDIS_PORT} \
    -t get,set \
    -n 100000 \
    -c 50 \
    -d 100 \
    --csv

echo ""
echo "=========================================="
echo "2. 다양한 명령어 벤치마크"
echo "=========================================="
redis-benchmark -h ${REDIS_HOST} -p ${REDIS_PORT} \
    -t get,set,incr,lpush,rpush,lpop,rpop,sadd,hset,spop,lrange \
    -n 10000 \
    -c 10 \
    -d 100 \
    --csv

echo ""
echo "=========================================="
echo "3. 동시 연결 수별 성능 테스트"
echo "=========================================="
for clients in 1 10 50 100; do
    echo "--- 동시 연결 수: ${clients} ---"
    redis-benchmark -h ${REDIS_HOST} -p ${REDIS_PORT} \
        -t get,set \
        -n 10000 \
        -c ${clients} \
        -d 100 \
        --csv | grep -E "GET|SET"
done

echo ""
echo "=========================================="
echo "4. 데이터 크기별 성능 테스트"
echo "=========================================="
for size in 10 100 1000 10000; do
    echo "--- 데이터 크기: ${size} bytes ---"
    redis-benchmark -h ${REDIS_HOST} -p ${REDIS_PORT} \
        -t get,set \
        -n 10000 \
        -c 10 \
        -d ${size} \
        --csv | grep -E "GET|SET"
done

echo ""
echo "=========================================="
echo "5. 파이프라인 테스트 (Pipeline)"
echo "=========================================="
redis-benchmark -h ${REDIS_HOST} -p ${REDIS_PORT} \
    -t get,set \
    -n 100000 \
    -c 50 \
    -d 100 \
    -P 16 \
    --csv

echo ""
echo "=========================================="
echo "Redis Benchmark 테스트 완료"
echo "=========================================="

