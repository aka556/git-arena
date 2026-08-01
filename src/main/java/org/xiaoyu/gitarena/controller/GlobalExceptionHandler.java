package org.xiaoyu.gitarena.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.xiaoyu.gitarena.domain.Result;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.service.LevelException;

/**
 * 全局异常处理：把可预期的命令错误与参数校验错误包装成 {@link Result}，避免向前端泄漏堆栈。
 * <p>HTTP 状态保持 200（错误经 Result.code 表达），便于前端统一在拦截器里读取 message。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 用户命令错误（非法命令、越权路径、无仓库等）——面向终端的友好提示。 */
    @ExceptionHandler(CommandException.class)
    public Result<Void> handleCommand(CommandException e) {
        return Result.fail(400, e.getMessage());
    }

    /** 关卡不存在 / 不合契约等——面向使用者的友好提示。 */
    @ExceptionHandler(LevelException.class)
    public Result<Void> handleLevel(LevelException e) {
        return Result.fail(400, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : "参数校验失败";
        return Result.fail(400, msg);
    }

    /** 兜底：未预期异常记录日志并返回通用错误，不外泄细节。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return Result.fail(500, "服务器内部错误");
    }
}
