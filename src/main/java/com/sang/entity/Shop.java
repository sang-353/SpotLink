package com.sang.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop")
@Schema(description = "商铺信息")
public class Shop implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键 ID", example = "1")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "商铺名称", example = "星巴克（国贸店）")
    private String name;

    @Schema(description = "商铺类型 ID（关联 tb_shop_type）", example = "1")
    private Long typeId;

    @Schema(description = "商铺图片，多个以逗号分隔", example = "/imgs/shop/1.jpg,/imgs/shop/2.jpg")
    private String images;

    @Schema(description = "商圈", example = "陆家嘴")
    private String area;

    @Schema(description = "详细地址", example = "上海市浦东新区陆家嘴环路 1000 号")
    private String address;

    @Schema(description = "经度", example = "121.506379")
    private Double x;

    @Schema(description = "纬度", example = "31.245414")
    private Double y;

    @Schema(description = "人均价格（元）", example = "45")
    private Long avgPrice;

    @Schema(description = "销量", example = "1280")
    private Integer sold;

    @Schema(description = "评论数量", example = "256")
    private Integer comments;

    @Schema(description = "评分（1~5 分，乘 10 存储避免小数）", example = "45")
    private Integer score;

    @Schema(description = "营业时间", example = "10:00-22:00")
    private String openHours;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "距离（米，GEO 查询时动态填充，非数据库字段）", example = "1234.5")
    @TableField(exist = false)
    private Double distance;
}
