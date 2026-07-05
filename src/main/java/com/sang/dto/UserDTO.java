package com.sang.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户基本信息（用于 ThreadLocal 传递和 Redis Hash 存储）")
public class UserDTO {
    @Schema(description = "用户 ID", example = "1")
    private Long id;
    @Schema(description = "用户昵称", example = "user_abc123")
    private String nickName;
    @Schema(description = "用户头像 URL", example = "/imgs/avatar/1.jpg")
    private String icon;
}
