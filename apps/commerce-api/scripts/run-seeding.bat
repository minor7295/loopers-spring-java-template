@echo off
REM 데이터 시딩 실행 스크립트 (Windows)
REM 사용법: scripts\run-seeding.bat

echo === 데이터 시딩 스크립트 실행 ===
echo.

REM 프로젝트 루트로 이동
cd /d "%~dp0\..\.."

REM Gradle을 사용하여 데이터 시딩 실행
gradlew.bat :apps:commerce-api:runSeeding

echo.
echo === 데이터 시딩 완료 ===

pause

