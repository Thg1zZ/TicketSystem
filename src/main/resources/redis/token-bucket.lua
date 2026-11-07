-- Token bucket: each request takes a token; tokens refill continuously up to the capacity (allowed burst).
-- KEYS[1] bucket (hash: tokens, at). ARGV[1] capacity, ARGV[2] refill per second.
-- Returns {allowed (1/0), milliseconds until the next token when refused}.
local time = redis.call('TIME') -- Redis clock: every replica agrees on "now"
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'at')
local tokens = tonumber(bucket[1]) or capacity
local at = tonumber(bucket[2]) or now
tokens = math.min(capacity, tokens + math.max(0, now - at) * refill / 1000)

local allowed = 0
local retryAfter = math.ceil((1 - tokens) * 1000 / refill)
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
    retryAfter = 0
end
redis.call('HSET', KEYS[1], 'tokens', tokens, 'at', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill * 1000) + 1000) -- a full bucket needs no key
return {allowed, retryAfter}
