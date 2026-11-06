-- All-or-nothing claim. Redis runs a script without interleaving other commands, so check + set is atomic.
-- KEYS: one key per seat. ARGV[1] = hold id, ARGV[2] = now (epoch ms), ARGV[3] = hold expiration (epoch ms).
-- Value "<holdId>:<expiresAtMs>": a claim past its expiration counts as free even if the TTL has not fired yet.
-- Returns the 1-based positions of taken keys (empty = success).
local now = tonumber(ARGV[2])
local expiresAt = tonumber(ARGV[3])

local taken = {}
for i, key in ipairs(KEYS) do
    local current = redis.call('GET', key)
    if current then
        local currentExpiresAt = tonumber(string.match(current, ':(%d+)$'))
        if currentExpiresAt > now then
            table.insert(taken, i)
        end
    end
end

if #taken == 0 then
    for _, key in ipairs(KEYS) do
        redis.call('SET', key, ARGV[1] .. ':' .. ARGV[3], 'PX', expiresAt - now)
    end
end
return taken
