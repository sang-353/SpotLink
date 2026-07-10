package com.sang.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = "E:\\code\\Maven\\hmdp\\nginx-1.18.0\\nginx-1.18.0\\html\\hmdp\\imgs\\";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;

    // RabbitMQ 交换机、队列、路由键常量
    // ========== 秒杀业务 ==========
    public static final String SECKILL_EXCHANGE = "spotlink.seckill.exchange";
    public static final String SECKILL_QUEUE = "spotlink.seckill.queue";
    public static final String SECKILL_ROUTING_KEY = "spotlink.seckill.order";

    // ========== 死信队列 ==========
    public static final String SECKILL_DLX = "spotlink.seckill.dlx";
    public static final String SECKILL_DLQ = "spotlink.seckill.dlq";
    public static final String SECKILL_DLX_ROUTING_KEY = "spotlink.seckill.dlx";

}
