-- Virtual queue of one event, in one atomic step: optionally join, admit whoever is due, report this user.
-- KEYS[1] waiting  (zset: user -> arrival number, so ZPOPMIN is first come, first served)
-- KEYS[2] admitted (zset: user -> pass expiration, epoch ms)
-- KEYS[3] state    (hash: seq = last arrival number, last = time of the last admission, epoch ms)
-- ARGV[1] user, ARGV[2] join (1/0), ARGV[3] admissions per second, ARGV[4] pass TTL (ms)
-- Returns {state, value, admitted now}: state 0 = not in queue, 1 = waiting (value = position),
-- 2 = admitted (value = pass expiration).
local time = redis.call('TIME') -- Redis clock: every replica agrees on "now"
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local user = ARGV[1]
local rate = tonumber(ARGV[3])
local passTtl = tonumber(ARGV[4])

if ARGV[2] ~= '1' and redis.call('EXISTS', KEYS[1], KEYS[2]) == 0 then
    return {0, 0, 0} -- only looking at a queue nobody joined: write nothing (no keys for arbitrary event ids)
end

redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now) -- expired passes leave; their owners must queue again

if ARGV[2] == '1' and not redis.call('ZSCORE', KEYS[2], user) and not redis.call('ZSCORE', KEYS[1], user) then
    redis.call('ZADD', KEYS[1], redis.call('HINCRBY', KEYS[3], 'seq', 1), user)
end

-- Admissions are paid for by elapsed time, like a token bucket: whoever calls first after the time passed
-- admits everyone due, so the global rate is right with any number of replicas and no scheduler.
-- At most one second of admissions accumulates (an idle queue does not bank a huge burst).
local last = tonumber(redis.call('HGET', KEYS[3], 'last')) or (now - 1000)
local due = math.min(math.floor((now - last) * rate / 1000), rate)
local admittedNow = 0
if due > 0 then
    local popped = redis.call('ZPOPMIN', KEYS[1], due)
    for i = 1, #popped, 2 do
        redis.call('ZADD', KEYS[2], now + passTtl, popped[i])
    end
    admittedNow = #popped / 2
    if admittedNow < due or now - last > 1000 then
        last = now -- queue drained or capped: unused admissions are dropped
    else
        last = last + due * 1000 / rate -- keep the fraction of the next admission already earned
    end
    redis.call('HSET', KEYS[3], 'last', last)
end

for _, key in ipairs(KEYS) do
    redis.call('PEXPIRE', key, 86400000) -- a queue nobody touches for a day disappears
end

local expiresAt = redis.call('ZSCORE', KEYS[2], user)
if expiresAt then
    return {2, tonumber(expiresAt), admittedNow}
end
local rank = redis.call('ZRANK', KEYS[1], user)
if rank then
    return {1, rank + 1, admittedNow}
end
return {0, 0, admittedNow}
