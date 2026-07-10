package com.sang.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage implements Serializable {
    /** 分布式订单 ID（RedisIdWorker 生成） */
    private Long orderId;

    /** 下单用户 ID */
    private Long userId;

    /** 秒杀券 ID */
    private Long voucherId;

    /** 当前重试次数（首次=0，每次重试+1） */
    private int retryCount;
}
