package com.campus.life.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.life.dto.LoginFormDTO;
import com.campus.life.dto.Result;
import com.campus.life.entity.User;

import javax.servlet.http.HttpSession;

/** 服务类 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
}
