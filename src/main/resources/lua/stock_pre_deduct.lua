-- Atomically reserve all requested SKU quantities.
--
-- KEYS[i] is the Redis stock key for one SKU and ARGV[i] is the quantity.
-- The validation pass intentionally happens before the mutation pass.  A
-- failed request therefore leaves every stock key unchanged.
--
-- Result: {success, failed_index, reason}
--   success = 1 on success, otherwise 0
--   reason = 1 insufficient stock, 2 missing key, 3 invalid quantity

for i = 1, #KEYS do
    local quantity = tonumber(ARGV[i])
    if not quantity or quantity <= 0 or quantity ~= math.floor(quantity) then
        return {0, i, 3}
    end

    if redis.call('EXISTS', KEYS[i]) == 0 then
        return {0, i, 2}
    end

    local available = tonumber(redis.call('GET', KEYS[i]))
    if not available or available < quantity then
        return {0, i, 1}
    end
end

for i = 1, #KEYS do
    redis.call('DECRBY', KEYS[i], ARGV[i])
end

return {1, 0, 0}
