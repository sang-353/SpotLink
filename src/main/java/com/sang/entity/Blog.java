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
@TableName("tb_blog")
@Schema(description = "探店笔记/博客")
public class Blog implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键 ID", example = "1")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "关联商铺 ID", example = "1")
    private Long shopId;

    @Schema(description = "作者用户 ID", example = "1")
    private Long userId;

    @Schema(description = "用户头像（非数据库字段，查询时动态填充）")
    @TableField(exist = false)
    private String icon;

    @Schema(description = "用户昵称（非数据库字段，查询时动态填充）")
    @TableField(exist = false)
    private String name;

    @Schema(description = "当前用户是否已点赞（非数据库字段）")
    @TableField(exist = false)
    private Boolean isLike;

    @Schema(description = "笔记标题", example = "发现了一家宝藏小店！")
    private String title;

    @Schema(description = "探店照片，最多 9 张，以逗号分隔", example = "/imgs/blog/1.jpg,/imgs/blog/2.jpg")
    private String images;

    @Schema(description = "探店文字描述", example = "这家店的咖啡真的很好喝...")
    private String content;

    @Schema(description = "点赞数量", example = "128")
    private Integer liked;

    @Schema(description = "评论数量", example = "32")
    private Integer comments;

    @Schema(description = "发布时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
