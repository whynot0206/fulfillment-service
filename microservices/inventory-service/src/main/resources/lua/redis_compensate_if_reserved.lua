-- Conditional compensation used by the ordinary MySQL reservation release.
-- It must not create a cancellation tombstone when no Redis reservation was
-- made, because ordinary MySQL orders do not own a Redis marker.
-- KEYS[1] cancellation marker, KEYS[2] reservation marker,
-- KEYS[3..N] stock keys. ARGV[1..N-2] are quantities and ARGV[N-1]
-- is the normalized order payload signature.
-- Result: {1 COMPENSATED, 2 ALREADY_COMPENSATED, 3 CONFLICT,
--          5 NO_RESERVATION, 0 UNKNOWN}.

local signature = ARGV[#ARGV]
local cancellation = redis.call('GET', KEYS[1])
if cancellation then
    if cancellation == signature then
        return {2, 0, 0}
    end
    return {3, 0, 0}
end

local reservation = redis.call('GET', KEYS[2])
if not reservation then
    return {5, 0, 0}
end
if reservation ~= signature then
    return {3, 0, 0}
end

for i = 3, #KEYS do
    local quantity = tonumber(ARGV[i - 2])
    if not quantity or quantity <= 0 or quantity ~= math.floor(quantity) then
        return {0, i - 2, 3}
    end
    if redis.call('EXISTS', KEYS[i]) == 0 then
        return {0, i - 2, 2}
    end
end

for i = 3, #KEYS do
    redis.call('INCRBY', KEYS[i], ARGV[i - 2])
end
redis.call('DEL', KEYS[2])
redis.call('SET', KEYS[1], signature, 'EX', 2592000)
return {1, 0, 0}
