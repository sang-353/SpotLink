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
@TableName("tb_voucher")
@Schema(description = "优惠券（普通券/秒杀券通用，秒杀库存和时间存在 SeckillVoucher 表）")
public class Voucher implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键 ID", example = "1")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属商铺 ID", example = "1")
    private Long shopId;

    @Schema(description = "代金券标题", example = "满100减20")
    private String title;

    @Schema(description = "副标题/使用须知", example = "仅限堂食使用")
    private String subTitle;

    @Schema(description = "使用规则（JSON）", example = "{}")
    private String rules;

    @Schema(description = "支付金额（分）", example = "8000")
    private Long payValue;

    @Schema(description = "抵扣金额（分）", example = "10000")
    private Long actualValue;

    @Schema(description = "优惠券类型：0=普通券, 1=秒杀券", example = "0")
    private Integer type;

    @Schema(description = "状态：0=下架, 1=上架", example = "1")
    private Integer status;

    @Schema(description = "库存（仅秒杀券有效，来自 SeckillVoucher 表）", example = "100")
    @TableField(exist = false)
    private Integer stock;

    @Schema(description = "秒杀开始时间（仅秒杀券有效）")
    @TableField(exist = false)
    private LocalDateTime beginTime;

    @Schema(description = "秒杀结束时间（仅秒杀券有效）")
    @TableField(exist = false)
    private LocalDateTime endTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
