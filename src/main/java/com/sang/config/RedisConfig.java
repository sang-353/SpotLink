package com.sang.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient() {
        // 配置类
        Config config = new Config();
        // 添加redis地址，此处为单点的地址，也可使用config.useClusterServers()配置集群地址
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("dev123456");
        // 创建客户端
        return Redisson.create(config);
    }

}
