-- Idempotent multi-SKU reservation.
-- KEYS[1] is the reservation marker, KEYS[2] the compensation marker,
-- KEYS[3..N] are stock keys. ARGV[1..N-2] are quantities and ARGV[N-1]
-- is their normalized signature.
-- Result: {1, 0, 0} applied, {2, 0, 0} already applied, or the same
-- validation failure shape as stock_pre_deduct.lua.  Reason 4 is an
-- idempotency conflict.

local signature = ARGV[#ARGV]
local existing_signature = redis.call('GET', KEYS[1])
if existing_signature then
    if existing_signature == signature then
        return {2, 0, 0}
    end
    return {0, 0, 4}
end

-- A completed compensation closes the previous attempt.  Reusing the same
-- operation id with the same request can therefore start a fresh attempt.
local existing_compensation = redis.call('GET', KEYS[2])
if existing_compensation then
    if existing_compensation == signature then
        redis.call('DEL', KEYS[2])
    else
        return {0, 0, 4}
    end
end

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
redis.call('SET', KEYS[1], signature, 'EX', 604800)
return {1, 0, 0}
