-- Atomically return quantities reserved by a previous stock reservation.
--
-- KEYS[i] is the Redis stock key for one SKU and ARGV[i] is the quantity.
-- The validation pass keeps a partial rollback from occurring if a key was
-- evicted or a caller supplied an invalid quantity.
--
-- Result: {success, failed_index, reason}
--   success = 1 on success, otherwise 0
--   reason = 2 missing key, 3 invalid quantity

for i = 1, #KEYS do
    local quantity = tonumber(ARGV[i])
    if not quantity or quantity <= 0 or quantity ~= math.floor(quantity) then
        return {0, i, 3}
    end

    if redis.call('EXISTS', KEYS[i]) == 0 then
        return {0, i, 2}
    end
end

for i = 1, #KEYS do
    redis.call('INCRBY', KEYS[i], ARGV[i])
end

return {1, 0, 0}
