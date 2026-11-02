-- 只有当前订单持有的预占才能释放；同一订单重试、旧消息迟到都不会多加库存。
local stockKey = 'seckill:stock:' .. ARGV[1]
local userKey = 'seckill:order:' .. ARGV[1]
local ownerKey = 'seckill:reservation:' .. ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local owner = redis.call('hget', ownerKey, userId)
if owner and owner ~= orderId then
    return 0
end
if redis.call('sismember', userKey, userId) == 0 then
    return 0
end
local stock = redis.call('get', stockKey)
if not stock or not string.match(stock, '^%-?%d+$')
    or tonumber(stock) < 0 or tonumber(stock) >= 2147483647 then
    return -1
end
-- owner 不存在时兼容升级前只有 set 标记的预占；SREM 本身仍保证只释放一次。
redis.call('srem', userKey, userId)
redis.call('hdel', ownerKey, userId)
redis.call('incrby', stockKey, 1)
return 1
