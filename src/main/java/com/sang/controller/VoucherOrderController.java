package com.sang.controller;


import com.sang.dto.Result;
import com.sang.service.IVoucherOrderService;
import com.sang.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀订单控制器 — 优惠券秒杀入口
 */
@RestController
@RequestMapping("/voucher-order")
@Tag(name = "秒杀订单", description = "高并发秒杀下单，基于 Lua 脚本原子操作 + RabbitMQ 消息队列异步落库")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Operation(
            summary = "秒杀优惠券",
            description = "高并发秒杀入口。流程：① 生成分布式订单 ID → ② Lua 脚本原子校验库存+一人一单 → ③ 消息队列异步创建订单。返回 0=成功, 1=库存不足, 2=不可重复下单"
    )
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(
            @Parameter(description = "秒杀优惠券 ID", required = true, example = "1")
            @PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @Operation(
            summary = "模拟支付",
            description = "模拟支付：将订单状态从 1(未支付) 改为 2(已支付)。真实场景应接入支付宝/微信支付"
    )
    @PutMapping("/pay/{orderId}")
    public Result payOrder(
            @Parameter(description = "订单 ID", required = true, example = "123456789")
            @PathVariable Long orderId) {
        Long userId = UserHolder.getUser().getId();
        return voucherOrderService.payOrder(orderId, userId);
    }
}
