package org.xiaoyu.gitarena.service;

import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.git.GitExecutor;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.CommandParser;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.security.PathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 参考解重放（docs/level-spec.md §6/§7）：把 SolutionSpec 的步骤在沙盒上机器执行。
 * {@code run} 走与用户终端相同的解析+执行链路（§3 黄金法则，不开后门）；
 * {@code writeFile} 以 Files API 模拟用户编辑文件，路径经 {@link PathGuard} 校验。
 *
 * <p>用于 §7 自证闭环（构建 initial → 重放 solution → 目标应达成）与未来"看答案"。
 */
@Component
public class SolutionReplayer {

    private final CommandParser parser;
    private final GitExecutor executor;
    private final PathGuard pathGuard;

    public SolutionReplayer(CommandParser parser, GitExecutor executor, PathGuard pathGuard) {
        this.parser = parser;
        this.executor = executor;
        this.pathGuard = pathGuard;
    }

    public void replay(SandboxRepo sandbox, LevelFile.SolutionSpec solution) {
        if (solution == null || solution.steps() == null) {
            return;
        }
        for (LevelFile.SolutionStep step : solution.steps()) {
            if (step.run() != null) {
                ParsedCommand cmd = parser.parse(step.run());
                // 冲突类 merge 会返回 ok=false 但状态已改变，属预期——继续后续步骤
                executor.execute(sandbox, cmd);
            } else if (step.writeFile() != null) {
                writeFile(sandbox, step.writeFile());
            } else {
                throw new CommandException("参考解步骤必须含 run 或 writeFile");
            }
        }
    }

    private void writeFile(SandboxRepo sandbox, LevelFile.WriteFile wf) {
        Path p = pathGuard.resolveInside(sandbox.root(), wf.path());
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, wf.content() == null ? "" : wf.content(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CommandException("参考解写文件失败：" + wf.path());
        }
    }
}
