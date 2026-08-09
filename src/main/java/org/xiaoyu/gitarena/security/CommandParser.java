package org.xiaoyu.gitarena.security;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令解析器：把终端原始输入切分为 {@link ParsedCommand}，并对命令做白名单校验。
 *
 * <p><b>安全要点（CLAUDE.md §7）</b>：本切分器<b>不做任何 shell 解释</b>——不展开 {@code $VAR}、
 * 不做通配符、不执行分号/反引号/变量替换。仅支持单/双引号包裹带空格的参数，并把受控管道
 * {@code |} 与输出重定向 {@code >}/{@code >>} 切成独立 token。最终参数以字符串数组交给纯 Java
 * 执行层，绝不拼接成 shell 字符串。
 */
@Component
public class CommandParser {

    private static final int MAX_LENGTH = 2000;

    public ParsedCommand parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new CommandException("命令为空");
        }
        String trimmed = raw.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw new CommandException("命令过长");
        }

        List<String> tokens = tokenize(trimmed);
        if (tokens.isEmpty()) {
            throw new CommandException("命令为空");
        }
        if (tokens.size() > 128) {
            throw new CommandException("命令参数过多");
        }

        String program = tokens.get(0);

        if ("git".equals(program)) {
            if (tokens.size() < 2) {
                throw new CommandException("请输入 git 子命令，例如：git status");
            }
            String sub = tokens.get(1);
            if (!CommandWhitelist.isGitSubcommand(sub)) {
                throw new CommandException("暂不支持或不允许的 git 子命令：" + sub);
            }
            return new ParsedCommand("git", sub, List.copyOf(tokens.subList(2, tokens.size())), trimmed);
        }

        if (CommandWhitelist.isHelper(program)) {
            return new ParsedCommand(program, null, List.copyOf(tokens.subList(1, tokens.size())), trimmed);
        }

        throw new CommandException("不允许的命令：" + program);
    }

    /**
     * 引号感知切分。规则：空白分隔；单引号内原样（含双引号）；双引号内支持 {@code \"} 与 {@code \\} 转义；
     * 未加引号的 {@code |}/{@code >}/{@code >>} 单独成词。不识别或执行其它 shell 元字符。
     */
    private List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        int i = 0;
        int n = input.length();

        while (i < n) {
            char c = input.charAt(i);

            if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
                i++;
                continue;
            }

            if (c == '>') {
                // 先结束当前 token，再把 > 或 >> 作为独立重定向 token
                if (inToken) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
                if (i + 1 < n && input.charAt(i + 1) == '>') {
                    tokens.add(">>");
                    i += 2;
                } else {
                    tokens.add(">");
                    i++;
                }
                continue;
            }

            if (c == '|') {
                if (i + 1 < n && input.charAt(i + 1) == '|') {
                    throw new CommandException("不支持逻辑运算符 ||");
                }
                if (inToken) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
                tokens.add("|");
                i++;
                continue;
            }

            if (c == '\'') {
                inToken = true;
                i++;
                while (i < n && input.charAt(i) != '\'') {
                    cur.append(input.charAt(i));
                    i++;
                }
                if (i >= n) {
                    throw new CommandException("引号未闭合");
                }
                i++; // 跳过收尾单引号
                continue;
            }

            if (c == '"') {
                inToken = true;
                i++;
                while (i < n && input.charAt(i) != '"') {
                    char d = input.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        char next = input.charAt(i + 1);
                        if (next == '"' || next == '\\') {
                            cur.append(next);
                            i += 2;
                            continue;
                        }
                    }
                    cur.append(d);
                    i++;
                }
                if (i >= n) {
                    throw new CommandException("引号未闭合");
                }
                i++; // 跳过收尾双引号
                continue;
            }

            inToken = true;
            cur.append(c);
            i++;
        }

        if (inToken) {
            tokens.add(cur.toString());
        }
        return tokens;
    }
}
