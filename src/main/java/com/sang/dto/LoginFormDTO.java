package com.sang.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登录表单")
public class LoginFormDTO {
    @Schema(description = "手机号码", requiredMode = Schema.RequiredMode.REQUIRED, example = "13800138000")
    private String phone;
    @Schema(description = "短信验证码（验证码登录时使用）", example = "123456")
    private String code;
    @Schema(description = "密码（密码登录时使用）", example = "abc123")
    private String password;
}
