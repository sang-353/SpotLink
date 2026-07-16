package com.sang.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单延迟消息 — 通过 x-delayed-message 交换机投递，用于 15 分钟超时自动取消
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderDelayMessage implements Serializable {
    /** 订单 ID */
    private Long orderId;
    /** 下单用户 ID（用于 Redis Set 回滚） */
    private Long userId;
    /** 秒杀券 ID（用于库存回滚） */
    private Long voucherId;
}
