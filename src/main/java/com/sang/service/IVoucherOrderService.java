package com.sang.service;

import com.sang.dto.Result;
import com.sang.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 模拟支付 — 将订单状态从 1(未支付) 改为 2(已支付)
     * @param orderId 订单 ID
     * @param userId  当前用户 ID
     */
    Result payOrder(Long orderId, Long userId);
}
