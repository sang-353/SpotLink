package com.sang.controller;


import com.sang.dto.Result;
import com.sang.service.IFollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 关注控制器 — 关注/取关、查询共同关注
 */
@RestController
@RequestMapping("/follow")
@Tag(name = "关注模块", description = "用户关注与取关、共同关注查询")
public class FollowController {
    @Resource
    private IFollowService followService;

    @Operation(summary = "关注/取关用户", description = "isFollow=true 关注，isFollow=false 取关，同时维护 DB 和 Redis")
    @PutMapping("/{id}/{isFollow}")
    public Result follow(
            @Parameter(description = "被关注用户的 ID", required = true, example = "2")
            @PathVariable("id") Long followUserId,
            @Parameter(description = "true=关注, false=取关", required = true, example = "true")
            @PathVariable Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    @Operation(summary = "判断是否已关注", description = "查询当前登录用户是否关注了指定用户")
    @GetMapping("or/not/{id}")
    public Result isFollow(
            @Parameter(description = "目标用户 ID", required = true, example = "2")
            @PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @Operation(summary = "查询共同关注", description = "查询当前用户与指定用户的共同关注列表，基于 Redis Set 交集运算")
    @GetMapping("/common/{id}")
    public Result followCommons(
            @Parameter(description = "目标用户 ID", required = true, example = "2")
            @PathVariable("id") Long id) {
        return followService.followCommons(id);
    }
}
