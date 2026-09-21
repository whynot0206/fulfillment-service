-- Idempotent multi-SKU Redis reservation.
-- KEYS[1] reservation marker, KEYS[2] cancellation tombstone,
-- KEYS[3..N] stock keys. ARGV[1..N-2] are quantities and ARGV[N-1]
-- is the normalized order payload signature.
-- Result: {1 RESERVED, 2 ALREADY_RESERVED, 3 CANCELED, 4 CONFLICT,
--          0 REJECTED with failed index/reason}.

local signature = ARGV[#ARGV]

local cancellation = redis.call('GET', KEYS[2])
if cancellation then
    if cancellation == signature then
        return {3, 0, 0}
    end
    return {4, 0, 0}
end

local reservation = redis.call('GET', KEYS[1])
if reservation then
    if reservation == signature then
        return {2, 0, 0}
    end
    return {4, 0, 0}
end

-- Validate every key before changing any stock. The request is all-or-nothing.
for i = 3, #KEYS do
    local quantity = tonumber(ARGV[i - 2])
    if not quantity or quantity <= 0 or quantity ~= math.floor(quantity) then
        return {0, i - 2, 3}
    end
    if redis.call('EXISTS', KEYS[i]) == 0 then
        return {0, i - 2, 2}
    end
    local available = tonumber(redis.call('GET', KEYS[i]))
    if not available or available < quantity then
        return {0, i - 2, 1}
    end
end

for i = 3, #KEYS do
    redis.call('DECRBY', KEYS[i], ARGV[i - 2])
end
-- Keep the marker beyond the maximum seven-day order timeout so a delayed
-- timeout scan can still compensate the Redis deduction.
redis.call('SET', KEYS[1], signature, 'EX', 2592000)
return {1, 0, 0}
