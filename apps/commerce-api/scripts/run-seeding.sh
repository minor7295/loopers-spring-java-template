#!/bin/bash

# 데이터 시딩 실행 스크립트
# 사용법: ./scripts/run-seeding.sh

set -e

echo "=== 데이터 시딩 스크립트 실행 ==="
echo ""

# 프로젝트 루트로 이동
cd "$(dirname "$0")/../.."

# Gradle을 사용하여 데이터 시딩 실행
./gradlew :apps:commerce-api:runSeeding

echo ""
echo "=== 데이터 시딩 완료 ==="

