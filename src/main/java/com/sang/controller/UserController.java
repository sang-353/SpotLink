package com.sang.controller;


import com.sang.dto.LoginFormDTO;
import com.sang.dto.Result;
import com.sang.dto.UserDTO;
import com.sang.entity.User;
import com.sang.entity.UserInfo;
import com.sang.service.IUserInfoService;
import com.sang.service.IUserService;
import com.sang.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器 — 登录、签到、个人信息
 */
@Slf4j
@RestController
@RequestMapping("/user")
@Tag(name = "用户模块", description = "用户登录、签到、个人信息管理")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Operation(summary = "发送手机验证码", description = "向指定手机号发送 6 位数字验证码，有效期 2 分钟")
    @PostMapping("code")
    public Result sendCode(
            @Parameter(description = "手机号码", required = true, example = "13800138000")
            @RequestParam("phone") String phone,
            HttpSession session) {
        return userService.sendCode(phone, session);
    }

    @Operation(summary = "用户登录", description = "支持手机验证码登录或密码登录，返回身份令牌 token")
    @PostMapping("/login")
    public Result login(
            @Parameter(description = "登录表单（phone + code 或 phone + password）", required = true)
            @RequestBody LoginFormDTO loginForm,
            HttpSession session) {
        return userService.login(loginForm, session);
    }

    @Operation(summary = "用户登出", description = "清除 Redis 中的登录令牌和 ThreadLocal 中的用户信息")
    @PostMapping("/logout")
    public Result logout(
            @Parameter(description = "HTTP 请求对象，用于获取 Authorization 头中的 token")
            HttpServletRequest request) {
        return userService.logout(request);
    }

    @Operation(summary = "获取当前登录用户信息", description = "从 ThreadLocal 中读取当前请求的用户信息")
    @GetMapping("/me")
    public Result me() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @Operation(summary = "查询用户详情", description = "根据用户 ID 查询用户扩展信息（城市、简介、粉丝数等）")
    @GetMapping("/info/{id}")
    public Result info(
            @Parameter(description = "用户 ID", required = true, example = "1")
            @PathVariable("id") String idStr) {
        try {
            if (idStr == null || idStr.trim().isEmpty() || "undefined".equalsIgnoreCase(idStr)) {
                log.error("用户ID为空或无效: {}", idStr);
                return Result.fail("用户ID不能为空");
            }
            Long userId = Long.parseLong(idStr);
            UserInfo info = userInfoService.getById(userId);
            if (info == null) {
                return Result.ok();
            }
            info.setCreateTime(null);
            info.setUpdateTime(null);
            return Result.ok(info);
        } catch (NumberFormatException e) {
            log.error("用户ID格式错误: {}", idStr, e);
            return Result.fail("无效的用户ID格式");
        }
    }

    @Operation(summary = "根据 ID 查询用户基本信息", description = "返回用户昵称、头像等基础信息")
    @GetMapping("/{id}")
    public Result queryUserById(
            @Parameter(description = "用户 ID", required = true, example = "1")
            @PathVariable("id") Long id) {
        User user = userService.getById(id);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        return Result.ok(userDTO);
    }

    @Operation(summary = "每日签到", description = "使用 Redis BitMap 记录当月签到，key 格式 sign:{userId}:{yyyyMM}")
    @PostMapping("/sign")
    public Result sign() {
        return userService.sign();
    }

    @Operation(summary = "查询连续签到天数", description = "统计本月截止今日的连续签到天数，通过 BITFIELD 命令计算")
    @GetMapping("/sign/count")
    public Result signCount() {
        return userService.signCount();
    }
}
