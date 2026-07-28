package org.xiaoyu.gitarena.domain;

/**
 * 统一响应包装
 * code=0 表示成功，非 0 为业务/系统错误
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
