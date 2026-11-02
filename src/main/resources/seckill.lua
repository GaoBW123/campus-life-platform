local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local ownerKey = 'seckill:reservation:' .. voucherId

local stock = tonumber(redis.call('get',stockKey))
if not stock then
    return 3
end
if(stock <= 0) then
    return 1
end
if(redis.call('sismember',orderKey,userId) == 1) then

    return 2
end

-- 在修改库存前验证 ownerKey 的类型，避免脚本运行时错误造成部分写入。
redis.call('hget',ownerKey,userId)
redis.call('incrby',stockKey,-1)
redis.call('sadd',orderKey,userId)
redis.call('hset',ownerKey,userId,orderId)
return 0
