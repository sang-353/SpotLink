package com.sang.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sang.dto.Result;
import com.sang.dto.UserDTO;
import com.sang.entity.Blog;
import com.sang.service.IBlogService;
import com.sang.utils.SystemConstants;
import com.sang.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 博客控制器 — 探店笔记的发布、点赞、关注流、热榜
 */
@RestController
@RequestMapping("/blog")
@Tag(name = "博客社交", description = "探店笔记发布、点赞、关注流推送、热榜查询")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @Operation(summary = "发布探店笔记", description = "保存博客后推送到所有粉丝的收件箱（Redis ZSet），粉丝可按时间线查看")
    @PostMapping
    public Result saveBlog(
            @Parameter(description = "博客内容（标题、图片、文字、关联商铺等）", required = true)
            @RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @Operation(summary = "点赞/取消点赞", description = "已点赞则取消（ZSet 移除 + DB 减 1），未点赞则点赞（ZSet 添加 + DB 加 1）")
    @PutMapping("/like/{id}")
    public Result likeBlog(
            @Parameter(description = "博客 ID", required = true, example = "1")
            @PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @Operation(summary = "查询我的笔记", description = "分页查询当前登录用户发布的所有探店笔记")
    @GetMapping("/of/me")
    public Result queryMyBlog(
            @Parameter(description = "当前页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Operation(summary = "查询热门博客", description = "按点赞数降序排列，无需登录即可访问")
    @GetMapping("/hot")
    public Result queryHotBlog(
            @Parameter(description = "当前页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    @Operation(summary = "根据 ID 查询博客详情", description = "返回博客内容、作者信息、当前用户是否已点赞")
    @GetMapping("/{id}")
    public Result queryBlogById(
            @Parameter(description = "博客 ID", required = true, example = "1")
            @PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    @Operation(summary = "查询博客点赞用户 Top5", description = "返回最早点赞的前 5 名用户信息")
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(
            @Parameter(description = "博客 ID", required = true, example = "1")
            @PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    @Operation(summary = "按用户 ID 查询其发布的博客", description = "分页查看指定用户的所有探店笔记")
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @Parameter(description = "用户 ID", required = true, example = "1")
            @RequestParam("id") Long userId,
            @Parameter(description = "当前页码", example = "1")
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        Page<Blog> page = blogService.query().eq("user_id", userId)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Operation(
            summary = "查询关注流（收件箱）",
            description = "基于 Redis ZSet 实现的滚动分页。每次返回 3 条，用 lastId 和 offset 实现滚动加载。offset 用于处理同一时间戳的多条记录"
    )
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @Parameter(description = "上一页最后一条博客的时间戳（首次请求传当前时间戳）", required = true, example = "1700000000000")
            @RequestParam("lastId") Long max,
            @Parameter(description = "偏移量，用于跳过相同时间戳的记录（首次传 0）", example = "0")
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryBlogOfFollow(max, offset);
    }
}
