package org.xiaoyu.gitarena.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 认证相关请求/响应。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record SendCode(@NotBlank @Email String email) {}

    /** 注册：两种路径二合一——只填 username+password 直注；带 email+code 则走邮箱验证。 */
    public record Register(
            @NotBlank @Size(min = 2, max = 10)
            @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线") String username,
            @NotBlank @Size(min = 6, max = 64)
            @Pattern(regexp = "^[\\x21-\\x7E]+$", message = "密码只能包含英文字母、数字和英文符号，不支持中文或空格")
            @Pattern(regexp = ".*[^0-9].*", message = "密码不能是纯数字") String password,
            @NotBlank @Email String email,
            @NotBlank String code
    ) {}

    public record Login(@NotBlank String usernameOrEmail, @NotBlank String password) {}

    public record UserView(
            Long id,
            String username,
            String displayName,
            String email,
            boolean guest,
            int totalPoints,
            String avatarColor
    ) {}

    public record AuthResponse(String token, UserView user) {}
}
