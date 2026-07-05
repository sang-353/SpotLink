package com.sang.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "滚动分页结果（用于关注流等 ZSet 滚动查询）")
public class ScrollResult {
    @Schema(description = "当前页数据列表")
    private List<?> list;
    @Schema(description = "本页最小时间戳，作为下次请求的 lastId", example = "1700000000000")
    private Long minTime;
    @Schema(description = "偏移量，相同时间戳的记录需要跳过的条数", example = "1")
    private Integer offset;
}
