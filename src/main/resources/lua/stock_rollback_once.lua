-- Idempotent multi-SKU rollback.
-- KEYS[1] is the compensation marker, KEYS[2] the reservation marker,
-- KEYS[3..N] are stock keys. ARGV[1..N-2] are quantities and ARGV[N-1]
-- is their normalized signature.
-- Result: {1, 0, 0} applied, {2, 0, 0} already applied, or a failure.

local signature = ARGV[#ARGV]
local existing_compensation = redis.call('GET', KEYS[1])
if existing_compensation then
    if existing_compensation == signature then
        return {2, 0, 0}
    end
    return {0, 0, 4}
end

local reservation_signature = redis.call('GET', KEYS[2])
if not reservation_signature then
    return {0, 0, 2}
end
if reservation_signature ~= signature then
    return {0, 0, 4}
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
redis.call('SET', KEYS[1], signature, 'EX', 604800)
return {1, 0, 0}
