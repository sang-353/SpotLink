package com.sang.controller;


import com.sang.dto.Result;
import com.sang.service.IShopTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商铺类型控制器 — 分类列表查询
 */
@RestController
@RequestMapping("/shop-type")
@Tag(name = "商铺分类", description = "商铺类型（品类）列表查询，用于前端导航栏展示")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Operation(summary = "查询商铺类型列表", description = "返回所有商铺分类（如美食、酒店、娱乐等），按排序字段升序排列")
    @GetMapping("list")
    public Result queryTypeList() {
        return typeService.queryTypeList();
    }
}
