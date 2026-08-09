package org.xiaoyu.gitarena.git;

import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.security.CommandWhitelist;
import org.xiaoyu.gitarena.security.ParsedCommand;
import org.xiaoyu.gitarena.security.PathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 沙盒安全终端。所有命令都是 Java/NIO 内建实现：不启动进程、不调用宿主机 shell、不做变量或脚本展开。
 * 路径统一经过 {@link PathGuard}，并对读取、写入、输出、递归深度和条目数设置硬上限。
 */
@Component
public class SandboxShellExecutor {

    private static final int MAX_ARGUMENTS = 64;
    private static final int MAX_PIPE_STAGES = 6;
    private static final int MAX_OUTPUT_CHARS = 128 * 1024;
    private static final long MAX_READ_BYTES = 256 * 1024L;
    private static final long MAX_FILE_BYTES = 1024 * 1024L;
    private static final long MAX_COPY_BYTES = 4 * 1024 * 1024L;
    private static final int MAX_ENTRIES = 1000;
    private static final int MAX_DEPTH = 12;

    private static final Set<String> PIPE_COMMANDS = Set.of(
            "echo", "pwd", "ls", "cat", "head", "tail", "wc", "sort", "uniq", "grep", "find", "tree"
    );

    private static final String HELP = """
            安全终端内建命令：
              导航  pwd cd ls tree find
              文件  touch mkdir rmdir cp mv rm cat stat du
              文本  echo head tail wc grep sort uniq
              其它  clear whoami date help
            支持：单/双引号、受控管道 |、输出重定向 > 与 >>
            安全限制：仅可访问当前沙盒；禁止 .git、符号链接、脚本、宿主机进程与网络命令
            """;

    private final PathGuard pathGuard;

    public SandboxShellExecutor(PathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    public ExecOutput execute(SandboxRepo sandbox, ParsedCommand command) {
        if (!CommandWhitelist.isHelper(command.program())) {
            throw new CommandException("不允许的命令：" + command.program());
        }
        if (command.args().size() > MAX_ARGUMENTS) {
            throw new CommandException("命令参数过多");
        }

        ParsedPipeline parsed = parsePipeline(command);
        String input = null;
        for (Stage stage : parsed.stages()) {
            input = checkedOutput(dispatch(sandbox, stage.program(), stage.args(), input));
        }

        if (parsed.redirect() != null) {
            writeRedirect(sandbox, parsed.redirect(), input == null ? "" : input);
            return ExecOutput.ok("");
        }
        return ExecOutput.ok(input == null ? "" : input);
    }

    private String dispatch(SandboxRepo sandbox, String program, List<String> args, String stdin) {
        return switch (program) {
            case "help" -> noArgs(program, args, HELP.stripTrailing());
            case "pwd" -> noArgs(program, args, sandbox.displayCurrentDirectory());
            case "cd" -> cd(sandbox, args);
            case "ls" -> ls(sandbox, args);
            case "clear" -> noArgs(program, args, "\u001b[2J\u001b[H");
            case "cat" -> cat(sandbox, args, stdin);
            case "head" -> headOrTail(sandbox, args, stdin, true);
            case "tail" -> headOrTail(sandbox, args, stdin, false);
            case "wc" -> wc(sandbox, args, stdin);
            case "sort" -> sort(sandbox, args, stdin);
            case "uniq" -> uniq(sandbox, args, stdin);
            case "grep" -> grep(sandbox, args, stdin);
            case "mkdir" -> mkdir(sandbox, args);
            case "rmdir" -> rmdir(sandbox, args);
            case "touch" -> touch(sandbox, args);
            case "cp" -> cp(sandbox, args);
            case "mv" -> mv(sandbox, args);
            case "rm" -> rm(sandbox, args);
            case "echo" -> echo(args);
            case "find" -> find(sandbox, args);
            case "tree" -> tree(sandbox, args);
            case "stat" -> stat(sandbox, args);
            case "du" -> du(sandbox, args);
            case "whoami" -> noArgs(program, args, "player");
            case "date" -> noArgs(program, args, ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            default -> throw new CommandException("不允许的命令：" + program);
        };
    }

    private String cd(SandboxRepo sandbox, List<String> args) {
        if (args.size() > 1) {
            throw new CommandException("cd 只接受一个目录");
        }
        Path target = resolve(sandbox, args.isEmpty() ? "~" : args.get(0));
        if (!Files.isDirectory(target)) {
            throw new CommandException("目录不存在：" + displayInput(args, "~"));
        }
        sandbox.changeDirectory(target);
        return "";
    }

    private String ls(SandboxRepo sandbox, List<String> args) {
        boolean all = false;
        boolean longFormat = false;
        List<String> paths = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("-") && arg.length() > 1) {
                for (int i = 1; i < arg.length(); i++) {
                    if (arg.charAt(i) == 'a') all = true;
                    else if (arg.charAt(i) == 'l') longFormat = true;
                    else throw new CommandException("ls 不支持选项：-" + arg.charAt(i));
                }
            } else {
                paths.add(arg);
            }
        }
        if (paths.size() > 1) throw new CommandException("ls 当前只支持查看一个路径");
        Path target = resolve(sandbox, paths.isEmpty() ? "." : paths.get(0));
        if (!Files.exists(target)) throw new CommandException("路径不存在：" + displayInput(paths, "."));
        if (!Files.isDirectory(target)) return formatEntry(target, longFormat);
        boolean includeHidden = all;

        try (var stream = Files.list(target)) {
            List<Path> entries = stream
                    .filter(path -> !isGitMetadata(sandbox, path))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> includeHidden || !path.getFileName().toString().startsWith("."))
                    .sorted(entryComparator())
                    .limit(MAX_ENTRIES + 1L)
                    .toList();
            requireEntryLimit(entries.size());
            List<String> lines = new ArrayList<>();
            if (all) {
                lines.add(longFormat ? "d          0 ." : ".");
                lines.add(longFormat ? "d          0 .." : "..");
            }
            for (Path entry : entries) lines.add(formatEntry(entry, longFormat));
            return String.join("\n", lines);
        } catch (IOException e) {
            throw new CommandException("无法读取目录");
        }
    }

    private String cat(SandboxRepo sandbox, List<String> args, String stdin) {
        if (args.isEmpty()) {
            if (stdin == null) throw new CommandException("cat 需要文件名或管道输入");
            return stdin;
        }
        List<String> chunks = new ArrayList<>();
        for (String arg : args) chunks.add(readText(sandbox, arg));
        return String.join("", chunks);
    }

    private String headOrTail(SandboxRepo sandbox, List<String> args, String stdin, boolean head) {
        int count = 10;
        List<String> files = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-n".equals(arg)) {
                if (++i >= args.size()) throw new CommandException((head ? "head" : "tail") + " 的 -n 缺少数量");
                count = parseBoundedInt(args.get(i), 0, 1000, "行数");
            } else if (arg.matches("-\\d+")) {
                count = parseBoundedInt(arg.substring(1), 0, 1000, "行数");
            } else {
                files.add(arg);
            }
        }
        String text = textInput(sandbox, files, stdin, head ? "head" : "tail");
        List<String> lines = text.lines().toList();
        int from = head ? 0 : Math.max(0, lines.size() - count);
        int to = head ? Math.min(count, lines.size()) : lines.size();
        return String.join("\n", lines.subList(from, to));
    }

    private String wc(SandboxRepo sandbox, List<String> args, String stdin) {
        boolean linesOnly = args.remove("-l");
        boolean wordsOnly = args.remove("-w");
        boolean bytesOnly = args.remove("-c");
        if (args.stream().anyMatch(arg -> arg.startsWith("-"))) throw new CommandException("wc 仅支持 -l/-w/-c");
        String text = textInput(sandbox, args, stdin, "wc");
        long lines = text.isEmpty() ? 0 : text.lines().count();
        long words = text.isBlank() ? 0 : text.strip().split("\\s+").length;
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (linesOnly || wordsOnly || bytesOnly) {
            List<String> values = new ArrayList<>();
            if (linesOnly) values.add(Long.toString(lines));
            if (wordsOnly) values.add(Long.toString(words));
            if (bytesOnly) values.add(Long.toString(bytes));
            return String.join(" ", values);
        }
        return lines + " " + words + " " + bytes;
    }

    private String sort(SandboxRepo sandbox, List<String> args, String stdin) {
        boolean reverse = args.remove("-r");
        boolean unique = args.remove("-u");
        if (args.stream().anyMatch(arg -> arg.startsWith("-"))) throw new CommandException("sort 仅支持 -r/-u");
        List<String> lines = new ArrayList<>(textInput(sandbox, args, stdin, "sort").lines().toList());
        lines.sort(reverse ? Comparator.reverseOrder() : Comparator.naturalOrder());
        if (unique) lines = new ArrayList<>(new java.util.LinkedHashSet<>(lines));
        return String.join("\n", lines);
    }

    private String uniq(SandboxRepo sandbox, List<String> args, String stdin) {
        boolean count = args.remove("-c");
        if (args.stream().anyMatch(arg -> arg.startsWith("-"))) throw new CommandException("uniq 仅支持 -c");
        List<String> lines = textInput(sandbox, args, stdin, "uniq").lines().toList();
        List<String> output = new ArrayList<>();
        for (int i = 0; i < lines.size();) {
            int end = i + 1;
            while (end < lines.size() && lines.get(end).equals(lines.get(i))) end++;
            output.add(count ? String.format(Locale.ROOT, "%7d %s", end - i, lines.get(i)) : lines.get(i));
            i = end;
        }
        return String.join("\n", output);
    }

    private String grep(SandboxRepo sandbox, List<String> args, String stdin) {
        boolean ignoreCase = false;
        boolean lineNumber = false;
        boolean recursive = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if ("-i".equals(arg)) ignoreCase = true;
            else if ("-n".equals(arg)) lineNumber = true;
            else if ("-r".equals(arg) || "-R".equals(arg)) recursive = true;
            else if (arg.startsWith("-")) throw new CommandException("grep 仅支持 -i/-n/-r");
            else positional.add(arg);
        }
        if (positional.isEmpty()) throw new CommandException("grep 需要搜索文本");
        String needle = positional.remove(0);
        if (needle.length() > 256) throw new CommandException("grep 搜索文本过长");

        List<NamedText> sources = new ArrayList<>();
        if (positional.isEmpty()) {
            if (stdin == null) throw new CommandException("grep 需要文件或管道输入");
            sources.add(new NamedText("", stdin));
        } else {
            for (String arg : positional) {
                Path path = resolve(sandbox, arg);
                if (Files.isDirectory(path)) {
                    if (!recursive) throw new CommandException("grep 读取目录需要 -r：" + arg);
                    sources.addAll(recursiveTexts(sandbox, path));
                } else {
                    sources.add(new NamedText(arg, readText(path, arg)));
                }
            }
        }

        String comparedNeedle = ignoreCase ? needle.toLowerCase(Locale.ROOT) : needle;
        boolean prefixFile = sources.size() > 1 || recursive;
        List<String> output = new ArrayList<>();
        for (NamedText source : sources) {
            List<String> lines = source.text().lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                String compared = ignoreCase ? lines.get(i).toLowerCase(Locale.ROOT) : lines.get(i);
                if (!compared.contains(comparedNeedle)) continue;
                StringBuilder line = new StringBuilder();
                if (prefixFile) line.append(source.name()).append(':');
                if (lineNumber) line.append(i + 1).append(':');
                line.append(lines.get(i));
                output.add(line.toString());
                if (output.size() > MAX_ENTRIES) throw new CommandException("grep 结果过多，请缩小范围");
            }
        }
        return String.join("\n", output);
    }

    private String mkdir(SandboxRepo sandbox, List<String> args) {
        boolean parents = args.remove("-p");
        requirePaths("mkdir", args);
        for (String arg : args) {
            Path path = resolve(sandbox, arg);
            try {
                if (parents) Files.createDirectories(path);
                else Files.createDirectory(path);
            } catch (IOException e) {
                throw new CommandException("无法创建目录：" + arg);
            }
        }
        return "";
    }

    private String rmdir(SandboxRepo sandbox, List<String> args) {
        requirePaths("rmdir", args);
        for (String arg : args) {
            Path path = resolve(sandbox, arg);
            rejectRootOrCurrentMutation(sandbox, path);
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new CommandException("目录不存在或非空：" + arg);
            }
        }
        return "";
    }

    private String touch(SandboxRepo sandbox, List<String> args) {
        requirePaths("touch", args);
        for (String arg : args) {
            Path path = resolve(sandbox, arg);
            try {
                Files.createDirectories(path.getParent());
                if (!Files.exists(path)) Files.createFile(path);
                else if (Files.isDirectory(path)) throw new CommandException("不能 touch 目录：" + arg);
                else Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
            } catch (IOException e) {
                throw new CommandException("无法创建文件：" + arg);
            }
        }
        return "";
    }

    private String cp(SandboxRepo sandbox, List<String> args) {
        boolean recursive = args.remove("-r");
        recursive = args.remove("-R") || recursive;
        if (args.size() != 2) throw new CommandException("cp 用法：cp [-r] <来源> <目标>");
        Path source = resolve(sandbox, args.get(0));
        Path target = resolve(sandbox, args.get(1));
        if (!Files.exists(source)) throw new CommandException("来源不存在：" + args.get(0));
        target = destinationFor(sandbox, source, target);
        if (Files.isDirectory(source)) {
            if (!recursive) throw new CommandException("复制目录需要 cp -r");
            copyTree(sandbox, source, target);
        } else {
            copyFile(source, target, args.get(0));
        }
        return "";
    }

    private String mv(SandboxRepo sandbox, List<String> args) {
        if (args.size() != 2) throw new CommandException("mv 用法：mv <来源> <目标>");
        Path source = resolve(sandbox, args.get(0));
        Path target = resolve(sandbox, args.get(1));
        if (!Files.exists(source)) throw new CommandException("来源不存在：" + args.get(0));
        rejectRootOrCurrentMutation(sandbox, source);
        target = destinationFor(sandbox, source, target);
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new CommandException("无法移动：" + args.get(0));
        }
        return "";
    }

    private String rm(SandboxRepo sandbox, List<String> args) {
        boolean recursive = false;
        boolean force = false;
        List<String> paths = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("-") && arg.length() > 1) {
                for (int i = 1; i < arg.length(); i++) {
                    if (arg.charAt(i) == 'r' || arg.charAt(i) == 'R') recursive = true;
                    else if (arg.charAt(i) == 'f') force = true;
                    else throw new CommandException("rm 不支持选项：-" + arg.charAt(i));
                }
            } else paths.add(arg);
        }
        requirePaths("rm", paths);
        for (String arg : paths) {
            Path path = resolve(sandbox, arg);
            rejectRootOrCurrentMutation(sandbox, path);
            if (!Files.exists(path)) {
                if (!force) throw new CommandException("路径不存在：" + arg);
                continue;
            }
            try {
                if (Files.isDirectory(path)) {
                    if (!recursive) throw new CommandException("删除目录需要 rm -r：" + arg);
                    deleteTree(sandbox, path);
                } else {
                    Files.delete(path);
                }
            } catch (IOException e) {
                throw new CommandException("无法删除：" + arg);
            }
        }
        return "";
    }

    private String echo(List<String> args) {
        return String.join(" ", args);
    }

    private String find(SandboxRepo sandbox, List<String> args) {
        String startArg = ".";
        String namePattern = null;
        Character type = null;
        int maxDepth = MAX_DEPTH;
        int i = 0;
        if (i < args.size() && !args.get(i).startsWith("-")) startArg = args.get(i++);
        while (i < args.size()) {
            String option = args.get(i++);
            if (i >= args.size()) throw new CommandException("find 选项缺少参数：" + option);
            String value = args.get(i++);
            if ("-name".equals(option)) namePattern = value;
            else if ("-type".equals(option) && ("f".equals(value) || "d".equals(value))) type = value.charAt(0);
            else if ("-maxdepth".equals(option)) maxDepth = parseBoundedInt(value, 0, MAX_DEPTH, "深度");
            else throw new CommandException("find 仅支持 -name/-type/-maxdepth");
        }
        Path start = resolve(sandbox, startArg);
        if (!Files.exists(start)) throw new CommandException("路径不存在：" + startArg);
        PathMatcher matcher;
        try {
            matcher = namePattern == null ? null : start.getFileSystem().getPathMatcher("glob:" + namePattern);
        } catch (IllegalArgumentException e) {
            throw new CommandException("find -name pattern is invalid");
        }
        List<String> output = new ArrayList<>();
        try (var stream = Files.walk(start, maxDepth)) {
            for (Path path : stream.sorted().toList()) {
                if (isGitMetadata(sandbox, path) || Files.isSymbolicLink(path)) continue;
                if (matcher != null && !matcher.matches(path.getFileName())) continue;
                if (type != null && type == 'f' && !Files.isRegularFile(path)) continue;
                if (type != null && type == 'd' && !Files.isDirectory(path)) continue;
                output.add(relativeFrom(start, path));
                if (output.size() > MAX_ENTRIES) throw new CommandException("find 结果过多，请增加过滤条件");
            }
        } catch (IOException e) {
            throw new CommandException("find 无法读取路径");
        }
        return String.join("\n", output);
    }

    private String tree(SandboxRepo sandbox, List<String> args) {
        int depth = 4;
        String startArg = ".";
        for (int i = 0; i < args.size(); i++) {
            if ("-L".equals(args.get(i))) {
                if (++i >= args.size()) throw new CommandException("tree -L 缺少深度");
                depth = parseBoundedInt(args.get(i), 0, MAX_DEPTH, "深度");
            } else if (args.get(i).startsWith("-")) {
                throw new CommandException("tree 仅支持 -L");
            } else {
                startArg = args.get(i);
            }
        }
        Path start = resolve(sandbox, startArg);
        if (!Files.isDirectory(start)) throw new CommandException("目录不存在：" + startArg);
        List<String> lines = new ArrayList<>();
        lines.add(start.getFileName() == null ? "." : start.getFileName().toString());
        appendTree(sandbox, start, "", depth, lines);
        return String.join("\n", lines);
    }

    private String stat(SandboxRepo sandbox, List<String> args) {
        requirePaths("stat", args);
        List<String> output = new ArrayList<>();
        for (String arg : args) {
            Path path = resolve(sandbox, arg);
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                output.add("File: " + arg);
                output.add("Type: " + (attributes.isDirectory() ? "directory" : "file"));
                output.add("Size: " + attributes.size() + " bytes");
                output.add("Modified: " + attributes.lastModifiedTime());
            } catch (IOException e) {
                throw new CommandException("无法读取属性：" + arg);
            }
        }
        return String.join("\n", output);
    }

    private String du(SandboxRepo sandbox, List<String> args) {
        boolean human = args.remove("-h");
        if (args.size() > 1 || args.stream().anyMatch(arg -> arg.startsWith("-"))) throw new CommandException("du 用法：du [-h] [路径]");
        String input = args.isEmpty() ? "." : args.get(0);
        Path path = resolve(sandbox, input);
        long size = directorySize(sandbox, path);
        return (human ? humanBytes(size) : Long.toString(size)) + "\t" + input;
    }

    private ParsedPipeline parsePipeline(ParsedCommand command) {
        List<String> tokens = new ArrayList<>();
        tokens.add(command.program());
        tokens.addAll(command.args());

        Redirect redirect = null;
        int redirectIndex = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (">".equals(tokens.get(i)) || ">>".equals(tokens.get(i))) {
                if (redirectIndex >= 0) throw new CommandException("只能使用一次输出重定向");
                redirectIndex = i;
            }
        }
        if (redirectIndex >= 0) {
            if (redirectIndex != tokens.size() - 2) throw new CommandException("输出重定向必须位于命令末尾并指定一个文件");
            redirect = new Redirect(tokens.get(tokens.size() - 1), ">>".equals(tokens.get(redirectIndex)));
            tokens = new ArrayList<>(tokens.subList(0, redirectIndex));
        }

        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            if ("|".equals(token)) {
                if (current.isEmpty()) throw new CommandException("管道两侧都需要命令");
                groups.add(current);
                current = new ArrayList<>();
            } else current.add(token);
        }
        if (current.isEmpty()) throw new CommandException("管道末尾缺少命令");
        groups.add(current);
        if (groups.size() > MAX_PIPE_STAGES) throw new CommandException("管道层级过多");

        List<Stage> stages = new ArrayList<>();
        for (List<String> group : groups) {
            String program = group.get(0);
            if (!CommandWhitelist.isHelper(program)) throw new CommandException("管道中不允许的命令：" + program);
            if (groups.size() > 1 && !PIPE_COMMANDS.contains(program)) throw new CommandException("该命令不能用于管道：" + program);
            stages.add(new Stage(program, new ArrayList<>(group.subList(1, group.size()))));
        }
        return new ParsedPipeline(stages, redirect);
    }

    private void writeRedirect(SandboxRepo sandbox, Redirect redirect, String output) {
        Path target = resolve(sandbox, redirect.target());
        if (Files.isDirectory(target)) throw new CommandException("重定向目标不能是目录：" + redirect.target());
        String content = output;
        if (!content.isEmpty() && !content.endsWith("\n") && !content.endsWith("\r")) content += System.lineSeparator();
        long existing = 0;
        try {
            if (redirect.append() && Files.exists(target)) existing = Files.size(target);
            long bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (existing + bytes > MAX_FILE_BYTES) throw new CommandException("文件超过 1 MiB 写入上限");
            Files.createDirectories(target.getParent());
            if (redirect.append()) {
                Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(target, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new CommandException("无法写入文件：" + redirect.target());
        }
    }

    private String textInput(SandboxRepo sandbox, List<String> files, String stdin, String command) {
        if (files.isEmpty()) {
            if (stdin == null) throw new CommandException(command + " 需要文件或管道输入");
            return stdin;
        }
        List<String> chunks = new ArrayList<>();
        for (String file : files) chunks.add(readText(sandbox, file));
        return String.join("\n", chunks);
    }

    private String readText(SandboxRepo sandbox, String input) {
        return readText(resolve(sandbox, input), input);
    }

    private String readText(Path path, String input) {
        try {
            if (!Files.isRegularFile(path)) throw new CommandException("不是普通文件：" + input);
            long size = Files.size(path);
            if (size > MAX_READ_BYTES) throw new CommandException("文件过大，单次最多读取 256 KiB：" + input);
            byte[] bytes = Files.readAllBytes(path);
            for (byte value : bytes) if (value == 0) throw new CommandException("不显示二进制文件：" + input);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CommandException("无法读取文件：" + input);
        }
    }

    private List<NamedText> recursiveTexts(SandboxRepo sandbox, Path root) {
        List<NamedText> result = new ArrayList<>();
        try (var stream = Files.walk(root, MAX_DEPTH)) {
            for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
                if (isGitMetadata(sandbox, path) || Files.isSymbolicLink(path)) continue;
                result.add(new NamedText(sandbox.root().relativize(path).toString().replace('\\', '/'), readText(path, path.getFileName().toString())));
                if (result.size() > MAX_ENTRIES) throw new CommandException("递归文件过多，请缩小范围");
            }
        } catch (IOException e) {
            throw new CommandException("无法递归读取目录");
        }
        return result;
    }

    private Path destinationFor(SandboxRepo sandbox, Path source, Path target) {
        if (!Files.isDirectory(target)) return target;
        return pathGuard.resolveShellPath(sandbox.root(), target, source.getFileName().toString());
    }

    private void copyFile(Path source, Path target, String display) {
        try {
            long size = Files.size(source);
            if (size > MAX_COPY_BYTES) throw new CommandException("文件超过 4 MiB 复制上限：" + display);
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new CommandException("无法复制：" + display);
        }
    }

    private void copyTree(SandboxRepo sandbox, Path source, Path target) {
        if (target.startsWith(source)) throw new CommandException("不能把目录复制到其自身内部");
        int[] entries = {0};
        long[] bytes = {0};
        try {
            Files.walkFileTree(source, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(dir) || isGitMetadata(sandbox, dir)) return FileVisitResult.SKIP_SUBTREE;
                    checkEntryBudget(++entries[0]);
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                    checkEntryBudget(++entries[0]);
                    bytes[0] += attrs.size();
                    if (bytes[0] > MAX_COPY_BYTES) throw new CommandException("目录超过 4 MiB 复制上限");
                    Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new CommandException("无法复制目录");
        }
    }

    private void deleteTree(SandboxRepo sandbox, Path target) throws IOException {
        int[] entries = {0};
        Files.walkFileTree(target, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(dir) || isGitMetadata(sandbox, dir)) {
                    throw new CommandException("recursive delete cannot enter protected paths");
                }
                checkEntryBudget(++entries[0]);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || isGitMetadata(sandbox, file)) {
                    throw new CommandException("recursive delete cannot enter protected paths");
                }
                checkEntryBudget(++entries[0]);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void appendTree(SandboxRepo sandbox, Path directory, String prefix, int depth, List<String> output) {
        if (depth <= 0) return;
        List<Path> children;
        try (var stream = Files.list(directory)) {
            children = stream
                    .filter(path -> !isGitMetadata(sandbox, path))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted(entryComparator())
                    .toList();
        } catch (IOException e) {
            throw new CommandException("无法读取目录树");
        }
        for (int i = 0; i < children.size(); i++) {
            checkEntryBudget(output.size());
            Path child = children.get(i);
            boolean last = i == children.size() - 1;
            output.add(prefix + (last ? "└── " : "├── ") + child.getFileName() + (Files.isDirectory(child) ? "/" : ""));
            if (Files.isDirectory(child)) appendTree(sandbox, child, prefix + (last ? "    " : "│   "), depth - 1, output);
        }
    }

    private long directorySize(SandboxRepo sandbox, Path path) {
        if (Files.isRegularFile(path)) {
            try { return Files.size(path); } catch (IOException e) { throw new CommandException("无法读取文件大小"); }
        }
        long[] total = {0};
        int[] entries = {0};
        try (var stream = Files.walk(path, MAX_DEPTH)) {
            for (Path child : stream.toList()) {
                if (isGitMetadata(sandbox, child) || Files.isSymbolicLink(child)) continue;
                checkEntryBudget(++entries[0]);
                if (Files.isRegularFile(child)) total[0] += Files.size(child);
            }
        } catch (IOException e) {
            throw new CommandException("无法统计目录大小");
        }
        return total[0];
    }

    private String formatEntry(Path path, boolean longFormat) {
        String name = path.getFileName().toString() + (Files.isDirectory(path) ? "/" : "");
        if (!longFormat) return name;
        try {
            long size = Files.isDirectory(path) ? 0 : Files.size(path);
            return String.format(Locale.ROOT, "%s %10d %s", Files.isDirectory(path) ? "d" : "-", size, name);
        } catch (IOException e) {
            return "?          ? " + name;
        }
    }

    private Comparator<Path> entryComparator() {
        return Comparator.<Path, Boolean>comparing(path -> !Files.isDirectory(path))
                .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    private Path resolve(SandboxRepo sandbox, String input) {
        return pathGuard.resolveShellPath(sandbox.root(), sandbox.currentDirectory(), input);
    }

    private void rejectRootOrCurrentMutation(SandboxRepo sandbox, Path path) {
        if (path.equals(sandbox.root())) throw new CommandException("不允许删除或移动仓库根目录");
        Path cwd = sandbox.currentDirectory();
        if (cwd.equals(path) || cwd.startsWith(path)) throw new CommandException("不允许删除或移动当前目录及其上级");
    }

    private boolean isGitMetadata(SandboxRepo sandbox, Path path) {
        Path root = sandbox.root().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) return true;
        for (Path segment : root.relativize(normalized)) {
            if (".git".equalsIgnoreCase(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private String relativeFrom(Path start, Path path) {
        if (path.equals(start)) return ".";
        return "./" + start.relativize(path).toString().replace('\\', '/');
    }

    private String checkedOutput(String output) {
        String safe = output == null ? "" : output;
        if (safe.length() > MAX_OUTPUT_CHARS) throw new CommandException("命令输出超过 128 KiB，请缩小范围");
        return safe;
    }

    private int parseBoundedInt(String value, int min, int max, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new CommandException(label + "必须在 " + min + " 到 " + max + " 之间");
        }
    }

    private String noArgs(String command, List<String> args, String output) {
        if (!args.isEmpty()) throw new CommandException(command + " 不接受参数");
        return output;
    }

    private void requirePaths(String command, List<String> args) {
        if (args.isEmpty()) throw new CommandException(command + " 需要路径参数");
        if (args.size() > 32) throw new CommandException(command + " 一次最多处理 32 个路径");
    }

    private void requireEntryLimit(int size) {
        if (size > MAX_ENTRIES) throw new CommandException("目录条目过多，请缩小范围");
    }

    private void checkEntryBudget(int size) {
        if (size > MAX_ENTRIES) throw new CommandException("递归条目超过 1000 个安全上限");
    }

    private String displayInput(List<String> args, String fallback) {
        return args.isEmpty() ? fallback : args.get(0);
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1fK", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1fM", bytes / (1024.0 * 1024.0));
    }

    private record Stage(String program, List<String> args) {}

    private record Redirect(String target, boolean append) {}

    private record ParsedPipeline(List<Stage> stages, Redirect redirect) {}

    private record NamedText(String name, String text) {}
}
