package org.xiaoyu.gitarena.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xiaoyu.gitarena.domain.dto.AuthDtos;
import org.xiaoyu.gitarena.domain.entity.User;
import org.xiaoyu.gitarena.mapper.UserMapper;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.TokenStore;
import org.xiaoyu.gitarena.service.AuthService;
import org.xiaoyu.gitarena.service.VerificationCodeService;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String[] PALETTE = {
            "#2f80ed", "#eb5757", "#27ae60", "#f2994a", "#9b51e0", "#00b8d9", "#e91e63", "#795548"
    };
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    private final VerificationCodeService codeService;

    @Override
    public void sendCode(String email) {
        codeService.sendCode(email.toLowerCase());
    }

    @Override
    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.Register req) {
        String email = req.email() == null || req.email().isBlank() ? null : req.email().toLowerCase();
        // 带验证码 → 走邮箱验证路径；否则用户名+密码直注
        if (req.code() != null && !req.code().isBlank()) {
            if (email == null) {
                throw new CommandException("验证码注册需要提供邮箱");
            }
            codeService.verify(email, req.code());
        }
        if (findByUsername(req.username()) != null) {
            throw new CommandException("用户名已被占用");
        }
        if (email != null && findByEmail(email) != null) {
            throw new CommandException("邮箱已被注册");
        }

        User user = new User();
        user.setUsername(req.username());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setDisplayName(req.username());
        user.setIsGuest(false);
        user.setStatus("active");
        user.setAvatarColor(randomColor());
        user.setTotalPoints(0);
        userMapper.insert(user);

        return issue(user);
    }

    @Override
    public AuthDtos.AuthResponse login(AuthDtos.Login req) {
        String key = req.usernameOrEmail().trim();
        User user = key.contains("@") ? findByEmail(key.toLowerCase()) : findByUsername(key);
        if (user == null || Boolean.TRUE.equals(user.getIsGuest()) || user.getPasswordHash() == null
                || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new CommandException("用户名或密码错误");
        }
        if (!"active".equals(user.getStatus())) {
            throw new CommandException("账号已被禁用");
        }
        return issue(user);
    }

    @Override
    public AuthDtos.AuthResponse guest() {
        // guest-<6 位随机> 理论可撞 users_username_uq；重试几次换号，兜底 DB 唯一约束。
        for (int attempt = 0; attempt < 5; attempt++) {
            String suffix = String.format("%06d", RANDOM.nextInt(1_000_000));
            User user = new User();
            user.setUsername("guest-" + suffix);
            user.setDisplayName("游客" + suffix);
            user.setIsGuest(true);
            user.setStatus("active");
            user.setAvatarColor(randomColor());
            user.setTotalPoints(0);
            user.setExpiresAt(OffsetDateTime.now().plusHours(24)); // database.md：游客保留 24h
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException e) {
                continue; // 撞号，换一个后缀重试
            }
            String token = tokenStore.issue(user.getId(), true);
            return new AuthDtos.AuthResponse(token, toView(user));
        }
        throw new CommandException("游客创建失败，请重试");
    }

    @Override
    public void logout(String token) {
        tokenStore.revoke(token);
    }

    @Override
    public AuthDtos.UserView me(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new CommandException("用户不存在");
        }
        return toView(user);
    }

    private AuthDtos.AuthResponse issue(User user) {
        String token = tokenStore.issue(user.getId(), Boolean.TRUE.equals(user.getIsGuest()));
        return new AuthDtos.AuthResponse(token, toView(user));
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    private User findByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }

    private String randomColor() {
        return PALETTE[RANDOM.nextInt(PALETTE.length)];
    }

    private AuthDtos.UserView toView(User u) {
        return new AuthDtos.UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(),
                Boolean.TRUE.equals(u.getIsGuest()), u.getTotalPoints() == null ? 0 : u.getTotalPoints(),
                u.getAvatarColor());
    }
}
