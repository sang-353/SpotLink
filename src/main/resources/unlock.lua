---
--- Created by 35314.
--- DateTime: 2026/1/31 13:32
---
---比较线程标识与锁中标识是否一致
if (redis.call('GET', KEYS[1]) == ARGV[1]) then
    -- 一致，释放锁
    return redis.call('DEL', KEYS[1])
end
return 0