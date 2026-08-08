package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.AuthDtos;

/**
 * 认证服务（P1 用户体系）：邮箱验证码、注册（直注/邮箱验证两路径）、登录、游客、登出。
 */
public interface AuthService {

    void sendCode(String email);

    AuthDtos.AuthResponse register(AuthDtos.Register request);

    AuthDtos.AuthResponse login(AuthDtos.Login request);

    AuthDtos.AuthResponse guest();

    void logout(String token);

    AuthDtos.UserView me(Long userId);
}
