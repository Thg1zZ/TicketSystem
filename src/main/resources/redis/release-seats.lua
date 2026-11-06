-- Compare-and-delete: removes only keys still owned by this hold, never a newer claim by someone else.
-- KEYS: one key per seat. ARGV[1] = hold id. Returns how many keys were deleted.
local owner = ARGV[1] .. ':'
local released = 0
for _, key in ipairs(KEYS) do
    local current = redis.call('GET', key)
    if current and string.sub(current, 1, #owner) == owner then
        redis.call('DEL', key)
        released = released + 1
    end
end
return released
