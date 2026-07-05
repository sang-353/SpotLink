package com.sang.controller;


import com.sang.dto.Result;
import com.sang.entity.Voucher;
import com.sang.service.IVoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 优惠券控制器 — 普通券和秒杀券的创建与查询
 */
@RestController
@RequestMapping("/voucher")
@Tag(name = "优惠券管理", description = "普通优惠券和秒杀优惠券的创建与查询")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    @Operation(summary = "新增普通优惠券", description = "创建一张普通代金券，不包含秒杀库存和时间信息")
    @PostMapping
    public Result addVoucher(
            @Parameter(description = "优惠券信息（标题、金额、类型、所属商铺等）", required = true)
            @RequestBody Voucher voucher) {
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    @Operation(summary = "新增秒杀优惠券", description = "创建一张秒杀券，同时写入 tb_voucher 和 tb_seckill_voucher 两张表，并将库存预热到 Redis")
    @PostMapping("seckill")
    public Result addSeckillVoucher(
            @Parameter(description = "优惠券信息，包含秒杀库存 stock、开始时间 beginTime、结束时间 endTime", required = true)
            @RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    @Operation(summary = "查询店铺的优惠券列表", description = "根据商铺 ID 查询该店铺下所有可用的优惠券")
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(
            @Parameter(description = "商铺 ID", required = true, example = "1")
            @PathVariable("shopId") Long shopId) {
        return voucherService.queryVoucherOfShop(shopId);
    }
}
