-- Safe reconciliation correction for one stock key.
-- The write is applied only if the current value still equals the expected
-- value read by the reconciliation scan.  This prevents a scan from
-- overwriting a concurrent reserve or rollback.
-- Result: 1 changed, 0 key missing/value changed/invalid input.

local expected = tonumber(ARGV[1])
local corrected = tonumber(ARGV[2])
if not expected or expected < 0 or expected ~= math.floor(expected)
        or not corrected or corrected < 0 or corrected ~= math.floor(corrected) then
    return 0
end

local current = redis.call('GET', KEYS[1])
if not current or tonumber(current) ~= expected then
    return 0
end

redis.call('SET', KEYS[1], ARGV[2])
return 1
