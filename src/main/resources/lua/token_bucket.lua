-- Atomic token bucket admission.
--
-- KEYS[1] is a Redis hash.  ARGV contains capacity, refill rate (tokens per
-- second), requested token count, and an application millisecond timestamp.
-- Passing the timestamp keeps the write deterministic on Redis 3.x, where a
-- script that reads TIME and then writes can be rejected.  The TIME fallback
-- keeps the script usable by direct callers that omit the fourth argument.
--
-- Result: {allowed, remaining_tokens, now_millis}

local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])
if not capacity or capacity <= 0
        or not refill_rate or refill_rate < 0
        or not requested or requested <= 0
        or requested ~= math.floor(requested) then
    return {-1, '0', '0'}
end

local now_millis = tonumber(ARGV[4])
if not now_millis then
    local redis_time = redis.call('TIME')
    now_millis = tonumber(redis_time[1]) * 1000
            + math.floor(tonumber(redis_time[2]) / 1000)
end

local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
local last_refill_millis = tonumber(redis.call('HGET', KEYS[1], 'last_refill_millis'))
if not tokens then
    tokens = capacity
end
if not last_refill_millis then
    last_refill_millis = now_millis
end

if now_millis > last_refill_millis and refill_rate > 0 then
    local elapsed_seconds = (now_millis - last_refill_millis) / 1000
    tokens = math.min(capacity, tokens + elapsed_seconds * refill_rate)
end

local allowed = 0
-- Requests larger than the bucket capacity can never fit.  They are a normal
-- rejection and do not consume tokens.
if requested <= capacity and tokens >= requested then
    allowed = 1
    tokens = tokens - requested
end

-- Redis 3.x accepts one field/value pair per HSET invocation.  Keeping these
-- writes in the same Lua script still makes the whole state transition atomic.
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens))
redis.call('HSET', KEYS[1], 'last_refill_millis', tostring(now_millis))
redis.call('HSET', KEYS[1], 'capacity', tostring(capacity))
redis.call('HSET', KEYS[1], 'refill_rate', tostring(refill_rate))

return {allowed, tostring(tokens), tostring(now_millis)}
