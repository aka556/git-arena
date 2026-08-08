package org.xiaoyu.gitarena.security;

/**
 * 当前请求的登录用户（ThreadLocal，由 AuthInterceptor 在验签后设置、请求结束清除）。
 * 认证是<b>可选</b>的：未登录时为 null，匿名沙盒/关卡照常可玩；只有进度落库、/me 等需要用户。
 */
public final class CurrentUser {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(Long userId) {
        HOLDER.set(userId);
    }

    /** 当前用户 id；未登录为 null。 */
    public static Long id() {
        return HOLDER.get();
    }

    public static Long require() {
        Long id = HOLDER.get();
        if (id == null) {
            throw new CommandException("请先登录");
        }
        return id;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
