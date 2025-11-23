-- SCAN 기반 패턴 매칭 및 삭제 스크립트
-- KEYS: 없음 (패턴을 ARGV로 전달)
-- ARGV[1]: 패턴 (예: "product:list:brand:1:*")
-- 반환값: 삭제된 키의 개수

local pattern = ARGV[1]
local cursor = "0"
local deleted = 0
local batchSize = 100  -- 한 번에 처리할 키 개수

repeat
    local result = redis.call("SCAN", cursor, "MATCH", pattern, "COUNT", batchSize)
    cursor = result[1]
    local keys = result[2]
    
    -- 찾은 키들을 삭제
    for i = 1, #keys do
        redis.call("DEL", keys[i])
        deleted = deleted + 1
    end
until cursor == "0"

return deleted

