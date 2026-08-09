package org.xiaoyu.gitarena.git;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.util.FileUtils;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.security.CommandException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙盒仓库管理（P0：为每个会话创建隔离临时仓库；支持重置）。
 *
 * <p><b>登记边界</b>：会话沙盒的存活登记在内存（{@link ConcurrentHashMap}），数据库里的
 * {@code sandbox_repos} 只是<b>台账指针</b>（database.md §1/§3.2）——真相始终在文件系统。
 *
 * <p><b>两套回收互斥，不可重叠</b>：
 * <ul>
 *   <li>匿名会话与关卡沙盒无 owner 可挂台账（{@code owner_user_id NOT NULL}），由 {@link #reapIdle}
 *       按内存侧空闲时长回收——这是 CLAUDE.md §7.7 磁盘泄漏的主要堵口；</li>
 *   <li>房间相关沙盒已挂台账，生死由 {@code sandbox_repos.expires_at} 说了算（成员活动即滑动续期），
 *       须经 {@link #markLedgerManaged} 登记豁免。<b>否则内存侧的固定空闲阈值会抢先删掉目录</b>，
 *       连带用户尚未 push 的提交，而台账还显示 active——两套治理必须只有一个说了算。</li>
 * </ul>
 *
 * <p>本类同时是<b>沙盒目录布局的唯一真相</b>：会话沙盒与房间裸仓库分置两棵子树，
 * 台账里的 {@code sandbox_key} 经 {@link #resolveKey} 解析回绝对路径并强制限定在根内（CLAUDE.md §7.4）。
 */
@Slf4j
@Component
public class SandboxManager {

    /** 房间裸仓库的 key 前缀，对应 {@link #roomsBaseDir()} 子树。 */
    private static final String ROOM_KEY_PREFIX = "rooms/";

    private final Path baseDir;
    private final Path roomsBaseDir;
    private final Map<String, SandboxRepo> repos = new ConcurrentHashMap<>();
    /** 会话最后一次被访问的时刻，供 {@link #reapIdle} 判定空闲；只在内存，不入库。 */
    private final Map<String, Instant> lastActiveAt = new ConcurrentHashMap<>();
    /** 已挂台账、由 {@code sandbox_repos.expires_at} 治理的沙盒，内存空闲回收不得插手。 */
    private final Set<String> ledgerManaged = ConcurrentHashMap.newKeySet();

    public SandboxManager() {
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
            this.baseDir = Files.createDirectories(tmp.resolve("git-arena-sandboxes"));
            this.roomsBaseDir = Files.createDirectories(tmp.resolve("git-arena-rooms"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建沙盒根目录", e);
        }
    }

    /** 房间共享裸仓库（origin）的根目录：与会话沙盒分置，避免被会话回收误伤。 */
    public Path roomsBaseDir() {
        return roomsBaseDir;
    }

    /** 新建一个空工作目录（尚未 git init，交由用户执行 git init 学习首个命令）。 */
    public SandboxRepo create() {
        String sessionId = UUID.randomUUID().toString();
        Path dir = baseDir.resolve(sessionId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建沙盒目录", e);
        }
        SandboxRepo repo = new SandboxRepo(sessionId, dir);
        repos.put(sessionId, repo);
        touch(sessionId);
        log.debug("created sandbox {}", sessionId);
        return repo;
    }

    /** 按 sessionId 查找仍存活的沙盒；不存在返回 null（不抛异常，供"重连复用"判断）。 */
    public SandboxRepo find(String sessionId) {
        SandboxRepo repo = repos.get(sessionId);
        if (repo != null) {
            touch(sessionId);
        }
        return repo;
    }

    public SandboxRepo require(String sessionId) {
        SandboxRepo repo = repos.get(sessionId);
        if (repo == null) {
            throw new CommandException("会话不存在或已过期，请新建会话");
        }
        touch(sessionId);
        return repo;
    }

    /** 重置：清空并重建同一 sessionId 的工作目录（回到 git init 之前的空态；连同模拟远程一并清除）。 */
    public SandboxRepo reset(String sessionId) {
        SandboxRepo repo = require(sessionId);
        deleteQuietly(repo.root());
        deleteQuietly(repo.originPath());
        try {
            Files.createDirectories(repo.root());
        } catch (IOException e) {
            throw new IllegalStateException("无法重建沙盒目录", e);
        }
        repo.resetCurrentDirectory();
        return repo;
    }

    /**
     * 把台账里的相对 key 解析回绝对路径（database.md §3.2 存 key 不存绝对路径）。
     *
     * <p>key 命名空间：{@code rooms/<publicId>.git} 落在房间子树，其余（会话 UUID）落在会话子树。
     * 解析后强制校验仍在对应根内——台账行同样按不可信输入处理（CLAUDE.md §7.4）。
     */
    public Path resolveKey(String sandboxKey) {
        if (sandboxKey == null || sandboxKey.isBlank()) {
            throw new CommandException("沙盒 key 为空");
        }
        String normalized = sandboxKey.replace('\\', '/');
        boolean room = normalized.startsWith(ROOM_KEY_PREFIX);
        Path root = room ? roomsBaseDir : baseDir;
        String relative = room ? normalized.substring(ROOM_KEY_PREFIX.length()) : normalized;
        if (relative.isBlank() || relative.startsWith("/") || relative.matches("^[A-Za-z]:.*")) {
            throw new CommandException("非法沙盒 key：" + sandboxKey);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new CommandException("沙盒 key 越界：" + sandboxKey);
        }
        return resolved;
    }

    /**
     * 按台账 key 删除沙盒目录（连同会话沙盒的模拟远程兄弟目录），并摘除内存登记。
     * key 非法时只记日志不抛——回收作业不该因一行脏台账整批中断。
     */
    public void deleteByKey(String sandboxKey) {
        Path dir;
        try {
            dir = resolveKey(sandboxKey);
        } catch (CommandException e) {
            log.warn("跳过非法沙盒 key {}: {}", sandboxKey, e.getMessage());
            return;
        }
        deleteQuietly(dir);
        deleteQuietly(dir.resolveSibling(dir.getFileName() + ".origin.git"));
        forget(sandboxKey);
    }

    /**
     * 回收空闲超时的会话沙盒（CLAUDE.md §7.7 防磁盘泄漏）。
     *
     * <p>匿名/关卡沙盒没有 owner 可挂台账，只能按"最后活跃时刻"在内存侧判定。
     *
     * @return 实际回收的沙盒数
     */
    public int reapIdle(Duration idleTtl) {
        Instant deadline = Instant.now().minus(idleTtl);
        List<String> stale = new ArrayList<>();
        repos.forEach((sessionId, repo) -> {
            if (ledgerManaged.contains(sessionId)) {
                return; // 台账治理中：生死由 expires_at 决定，内存阈值无权处置
            }
            Instant seen = lastActiveAt.get(sessionId);
            if (seen == null || seen.isBefore(deadline)) {
                stale.add(sessionId);
            }
        });
        for (String sessionId : stale) {
            SandboxRepo repo = repos.remove(sessionId);
            lastActiveAt.remove(sessionId);
            if (repo != null) {
                deleteQuietly(repo.root());
                deleteQuietly(repo.originPath());
            }
        }
        if (!stale.isEmpty()) {
            log.info("回收空闲沙盒 {} 个（空闲超过 {}）", stale.size(), idleTtl);
        }
        return stale.size();
    }

    /**
     * 声明该沙盒已挂 {@code sandbox_repos} 台账，从内存空闲回收中豁免。
     * 由写台账的一方（当前是 {@code CollabServiceImpl}）在插入台账行后调用。
     */
    public void markLedgerManaged(String sandboxKey) {
        ledgerManaged.add(sandboxKey);
    }

    /** 当前存活的会话沙盒数（回收作业与测试观测用）。 */
    public int liveCount() {
        return repos.size();
    }

    private void touch(String sessionId) {
        lastActiveAt.put(sessionId, Instant.now());
    }

    /** 摘除内存登记（目录已在别处删除时调用），房间 key 不在会话登记里，忽略即可。 */
    private void forget(String sandboxKey) {
        repos.remove(sandboxKey);
        lastActiveAt.remove(sandboxKey);
        ledgerManaged.remove(sandboxKey);
    }

    @PreDestroy
    void cleanupAll() {
        repos.values().forEach(r -> {
            deleteQuietly(r.root());
            deleteQuietly(r.originPath());
        });
        repos.clear();
        lastActiveAt.clear();
        ledgerManaged.clear();
    }

    /**
     * 递归删除目录。用 JGit 的 FileUtils（RETRY 应对 Windows 上 .pack 文件的短暂锁定），
     * IGNORE_ERRORS 保证清理不因个别文件失败而中断。
     */
    private void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            FileUtils.delete(dir.toFile(),
                    FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.IGNORE_ERRORS);
        } catch (IOException e) {
            log.warn("failed to delete sandbox dir {}: {}", dir, e.getMessage());
        }
    }
}
