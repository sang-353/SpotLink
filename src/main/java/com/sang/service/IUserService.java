package com.sang.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sang.dto.LoginFormDTO;
import com.sang.dto.Result;
import com.sang.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;


/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result logout(HttpServletRequest request);

    Result sign();

    Result signCount();
}
