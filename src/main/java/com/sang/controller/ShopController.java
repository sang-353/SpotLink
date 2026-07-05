package com.sang.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sang.dto.Result;
import com.sang.entity.Shop;
import com.sang.service.IShopService;
import com.sang.utils.SystemConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 商铺控制器 — 商铺查询、新增、更新、地理位置搜索
 */
@RestController
@RequestMapping("/shop")
@Tag(name = "商铺模块", description = "商铺信息查询、新增、更新、按类型/名称/地理位置搜索")
public class ShopController {

    @Resource
    public IShopService shopService;

    @Operation(summary = "根据 ID 查询商铺详情", description = "支持缓存穿透防护，优先从 Redis 读取，缓存未命中时查 MySQL 并回写缓存")
    @GetMapping("/{id}")
    public Result queryShopById(
            @Parameter(description = "商铺 ID", required = true, example = "1")
            @PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    @Operation(summary = "新增商铺", description = "写入数据库并返回生成的商铺 ID")
    @PostMapping
    public Result saveShop(
            @Parameter(description = "商铺信息（名称、类型、坐标、地址等）", required = true)
            @RequestBody Shop shop) {
        shopService.save(shop);
        return Result.ok(shop.getId());
    }

    @Operation(summary = "更新商铺信息", description = "更新数据库后删除对应的 Redis 缓存，下次查询时自动重建缓存")
    @PutMapping
    public Result updateShop(
            @Parameter(description = "商铺信息（必须包含 id）", required = true)
            @RequestBody Shop shop) {
        return shopService.update(shop);
    }

    @Operation(summary = "按类型分页查询商铺", description = "支持传入经纬度进行附近搜索（5 公里范围内），结果按距离排序")
    @GetMapping("/of/type")
    public Result queryShopByType(
            @Parameter(description = "商铺类型 ID", required = true, example = "1")
            @RequestParam("typeId") Integer typeId,
            @Parameter(description = "当前页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @Parameter(description = "用户经度（可选，传入后按距离排序）", example = "116.397128")
            @RequestParam(value = "x", required = false) Double x,
            @Parameter(description = "用户纬度（可选，传入后按距离排序）", example = "39.916527")
            @RequestParam(value = "y", required = false) Double y
    ) {
        return shopService.queryShopByType(typeId, current, x, y);
    }

    @Operation(summary = "按名称关键字搜索商铺", description = "模糊匹配商铺名称，分页返回结果")
    @GetMapping("/of/name")
    public Result queryShopByName(
            @Parameter(description = "商铺名称关键字", example = "星巴克")
            @RequestParam(value = "name", required = false) String name,
            @Parameter(description = "当前页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }
}
