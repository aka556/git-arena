package org.xiaoyu.gitarena.git;

/**
 * 命令执行的文本输出。ok=false 时 stderr 承载友好错误信息（供终端红字显示）。
 */
public record ExecOutput(boolean ok, String stdout, String stderr) {

    public static ExecOutput ok(String stdout) {
        return new ExecOutput(true, stdout == null ? "" : stdout, "");
    }

    public static ExecOutput error(String stderr) {
        return new ExecOutput(false, "", stderr == null ? "" : stderr);
    }
}
