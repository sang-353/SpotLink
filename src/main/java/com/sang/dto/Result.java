package com.sang.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一响应体")
public class Result {
    @Schema(description = "是否成功", example = "true")
    private Boolean success;
    @Schema(description = "错误信息（失败时返回）", example = "库存不足")
    private String errorMsg;
    @Schema(description = "响应数据")
    private Object data;
    @Schema(description = "数据总数（分页时使用）", example = "100")
    private Long total;

    public static Result ok() {
        return new Result(true, null, null, null);
    }

    public static Result ok(Object data) {
        return new Result(true, null, data, null);
    }

    public static Result ok(List<?> data, Long total) {
        return new Result(true, null, data, total);
    }

    public static Result fail(String errorMsg) {
        return new Result(false, errorMsg, null, null);
    }
}
