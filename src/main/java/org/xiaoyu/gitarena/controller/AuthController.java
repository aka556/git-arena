package org.xiaoyu.gitarena.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.domain.dto.AuthDtos;
import org.xiaoyu.gitarena.security.CurrentUser;
import org.xiaoyu.gitarena.service.AuthService;

/**
 * 认证入口（薄 controller，§7）：验证码 / 注册（两路径）/ 登录 / 游客 / 登出 / 当前用户。
 *
 * <p>登录态是不透明 token（Redis 会话，见 {@link org.xiaoyu.gitarena.security.TokenStore}）；
 * 前端把它放进 {@code Authorization: Bearer <token>}，{@link org.xiaoyu.gitarena.security.AuthInterceptor}
 * 解析为 {@link CurrentUser}。本 controller 除 /me、/logout 外均无需登录。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 发送邮箱验证码（注册的邮箱验证路径用）。 */
    @PostMapping("/send-code")
    public Result<Void> sendCode(@Valid @RequestBody AuthDtos.SendCode request) {
        authService.sendCode(request.email());
        return Result.ok(null);
    }

    /** 注册：只填 username+password 直注；带 email+code 则先校验验证码。 */
    @PostMapping("/register")
    public Result<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.Register request) {
        return Result.ok(authService.register(request));
    }

    /** 登录：用户名或邮箱 + 密码。 */
    @PostMapping("/login")
    public Result<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.Login request) {
        return Result.ok(authService.login(request));
    }

    /** 游客登录：系统生成临时账号，24h 过期（database.md §3.1）。 */
    @PostMapping("/guest")
    public Result<AuthDtos.AuthResponse> guest() {
        return Result.ok(authService.guest());
    }

    /** 登出：吊销当前 token（读原始 Bearer 头，不依赖 CurrentUser）。幂等。 */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            authService.logout(header.substring(7).trim());
        }
        return Result.ok(null);
    }

    /** 当前登录用户（需登录，未登录由 {@link CurrentUser#require()} 抛"请先登录"）。 */
    @GetMapping("/me")
    public Result<AuthDtos.UserView> me() {
        return Result.ok(authService.me(CurrentUser.require()));
    }
}
