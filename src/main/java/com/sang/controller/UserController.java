package com.sang.controller;


import com.sang.dto.LoginFormDTO;
import com.sang.dto.Result;
import com.sang.dto.UserDTO;
import com.sang.entity.User;
import com.sang.entity.UserInfo;
import com.sang.service.IUserInfoService;
import com.sang.service.IUserService;
import com.sang.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;



/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 发送短信验证码并保存验证
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm, session);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request){
        // 实现登出功能
        return userService.logout(request);
    }

    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") String idStr) {
        try {
            // 处理空字符串、null、"undefined"等特殊情况
            if (idStr == null || idStr.trim().isEmpty() || "undefined".equalsIgnoreCase(idStr)) {
                log.error("用户ID为空或无效: {}", idStr);
                return Result.fail("用户ID不能为空");
            }
            Long userId = Long.parseLong(idStr);
            // 查询详情
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

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long id) {
        User user = userService.getById(id);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        return Result.ok(userDTO);
    }

    @PostMapping("/sign")
    public Result sign() {
    return userService.sign();
    }

    @GetMapping("/sign/count")
    public Result signCount() {
        return userService.signCount();
    }
}
