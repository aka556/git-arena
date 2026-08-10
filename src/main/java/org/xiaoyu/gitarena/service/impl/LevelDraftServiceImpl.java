package org.xiaoyu.gitarena.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xiaoyu.gitarena.domain.dto.LevelDraftDtos;
import org.xiaoyu.gitarena.domain.graph.GitGraph;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.git.GraphMapper;
import org.xiaoyu.gitarena.git.LevelBuilder;
import org.xiaoyu.gitarena.git.RepoInspector;
import org.xiaoyu.gitarena.git.SandboxManager;
import org.xiaoyu.gitarena.git.SandboxRepo;
import org.xiaoyu.gitarena.security.CommandException;
import org.xiaoyu.gitarena.service.GoalMatcher;
import org.xiaoyu.gitarena.service.LevelCatalog;
import org.xiaoyu.gitarena.service.LevelDraftService;
import org.xiaoyu.gitarena.service.LevelException;
import org.xiaoyu.gitarena.service.LevelValidator;
import org.xiaoyu.gitarena.service.MatchResult;
import org.xiaoyu.gitarena.service.SolutionReplayer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 关卡编辑器实现（M4）。草稿以 {@code levels} 表为真相（database.md §3.3），
 * <b>发布前必须通过自证闭环</b>（docs/level-spec.md §7）——这是关卡质量的唯一硬闸门：
 * 零步就能通关（配置错误）或参考解走不通（三份 spec 不互洽）的关卡一律不许上架。
 *
 * <p>自证在一次性临时沙盒里跑真实 JGit，与官方关卡的 CI 自证完全同一套代码路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LevelDraftServiceImpl implements LevelDraftService {

    /** 自定义 slug：小写字母数字与连字符，避免与路由/文件名语义打架。 */
    private static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");

    private static final String SELECT_MINE = """
            SELECT slug, title, category, difficulty, mode, status, visibility, updated_at
            FROM levels
            WHERE author_user_id = ? AND deleted_at IS NULL
            ORDER BY updated_at DESC
            """;

    private static final String SELECT_ONE = """
            SELECT slug, title, description, category, difficulty, order_index, mode, status, visibility,
                   initial_spec, goal_spec, solution_spec, schema_version, author_user_id
            FROM levels
            WHERE slug = ? AND deleted_at IS NULL
            """;

    private static final String UPSERT = """
            INSERT INTO levels (slug, title, description, category, difficulty, order_index, mode,
                                initial_spec, goal_spec, solution_spec, author_user_id,
                                visibility, status, schema_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, 'draft', ?)
            ON CONFLICT (slug) WHERE deleted_at IS NULL
            DO UPDATE SET title = EXCLUDED.title, description = EXCLUDED.description,
                          category = EXCLUDED.category, difficulty = EXCLUDED.difficulty,
                          order_index = EXCLUDED.order_index, mode = EXCLUDED.mode,
                          initial_spec = EXCLUDED.initial_spec, goal_spec = EXCLUDED.goal_spec,
                          solution_spec = EXCLUDED.solution_spec, visibility = EXCLUDED.visibility,
                          status = 'draft', schema_version = EXCLUDED.schema_version, updated_at = now()
            """;

    private final LevelCatalog catalog;
    private final LevelValidator validator;
    private final LevelBuilder levelBuilder;
    private final SandboxManager sandboxManager;
    private final GraphMapper graphMapper;
    private final GoalMatcher goalMatcher;
    private final RepoInspector repoInspector;
    private final SolutionReplayer solutionReplayer;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    @Override
    public List<LevelDraftDtos.DraftSummary> listMine(Long userId) {
        requireLogin(userId);
        return jdbc.query(SELECT_MINE, (rs, i) -> new LevelDraftDtos.DraftSummary(
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getShort("difficulty"),
                rs.getString("mode"),
                rs.getString("status"),
                rs.getString("visibility"),
                rs.getObject("updated_at", java.time.OffsetDateTime.class)), userId);
    }

    @Override
    public LevelDraftDtos.DraftDetail get(Long userId, String slug) {
        Row row = requireOwned(userId, slug);
        return new LevelDraftDtos.DraftDetail(row.slug, row.status, row.visibility, row.level);
    }

    @Override
    @Transactional
    public LevelDraftDtos.DraftDetail save(Long userId, LevelDraftDtos.SaveRequest request) {
        requireLogin(userId);
        String slug = request.slug();
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new CommandException("slug 需为 3–64 位小写字母/数字/连字符，且以字母开头");
        }
        if (catalog.has(slug)) {
            throw new CommandException("该 slug 属于官方关卡，请换一个：" + slug);
        }
        // 已存在的行必须属于当前作者（未占用则可新建）
        Row existing = findRow(slug);
        if (existing != null && !userId.equals(existing.authorUserId)) {
            throw new CommandException("该 slug 已被占用：" + slug);
        }

        LevelFile level = normalize(request.level(), slug);
        // 保存阶段只做语义校验（fail-closed），自证闭环留到发布——允许中途保存半成品
        validator.validate(level);

        LevelFile.Meta m = level.meta();
        jdbc.update(UPSERT,
                slug, m.title(), m.description(), m.category(), m.difficulty(),
                m.orderIndex(), m.mode(),
                json(level.initial()), json(level.goal()), json(level.solution()),
                userId, m.visibility() == null ? "public" : m.visibility(),
                level.specVersion());
        return get(userId, slug);
    }

    @Override
    public LevelDraftDtos.SelfCheckResult selfCheck(Long userId, String slug) {
        Row row = requireOwned(userId, slug);
        return runSelfCheck(row.level);
    }

    @Override
    @Transactional
    public LevelDraftDtos.SelfCheckResult publish(Long userId, String slug) {
        Row row = requireOwned(userId, slug);
        LevelDraftDtos.SelfCheckResult result = runSelfCheck(row.level);
        if (!result.ok()) {
            // fail-closed：不达标的关卡不许上架，问题原样回给编辑器
            throw new LevelException(slug, result.problems());
        }
        jdbc.update("UPDATE levels SET status = 'published', updated_at = now() WHERE slug = ? AND author_user_id = ?",
                slug, userId);
        return result;
    }

    @Override
    public void unpublish(Long userId, String slug) {
        requireOwned(userId, slug);
        jdbc.update("UPDATE levels SET status = 'draft', updated_at = now() WHERE slug = ? AND author_user_id = ?",
                slug, userId);
    }

    @Override
    public void delete(Long userId, String slug) {
        requireOwned(userId, slug);
        jdbc.update("UPDATE levels SET deleted_at = now(), updated_at = now() WHERE slug = ? AND author_user_id = ?",
                slug, userId);
    }

    /**
     * 自证闭环（docs/level-spec.md §7）：语义校验 → 零步不通关 → 参考解通关。
     * 在一次性沙盒里跑真实 JGit；沙盒无论成败都回收，避免草稿反复试跑撑爆磁盘（§7.7）。
     */
    private LevelDraftDtos.SelfCheckResult runSelfCheck(LevelFile level) {
        List<String> problems = new ArrayList<>();
        try {
            validator.validate(level);
        } catch (LevelException e) {
            return new LevelDraftDtos.SelfCheckResult(false, false, false, false, e.problems());
        }

        boolean zeroStepFails = false;
        boolean solutionPasses = false;
        SandboxRepo sandbox = sandboxManager.create();
        try {
            levelBuilder.build(level.initial(), sandbox);

            MatchResult zeroStep = validate(sandbox, level);
            zeroStepFails = !zeroStep.passed();
            if (!zeroStepFails) {
                problems.add("初始状态就已达成目标——玩家一进门就赢了，请检查 initial 与 goal 的差异");
            }

            if ("collab".equals(level.meta().mode())) {
                // collab 关卡需多人交互（房间/PR），单进程无法重放参考解——放行这一项
                solutionPasses = true;
            } else if (level.solution() == null || level.solution().steps() == null
                    || level.solution().steps().isEmpty()) {
                problems.add("缺少参考解（solution.steps）：发布前必须能机器重放通关");
            } else {
                try {
                    solutionReplayer.replay(sandbox, level.solution());
                    MatchResult solved = validate(sandbox, level);
                    solutionPasses = solved.passed();
                    if (!solutionPasses) {
                        problems.add("重放参考解后仍未达成目标：" + String.join("；", solved.reasons()));
                    }
                } catch (RuntimeException e) {
                    problems.add("参考解执行失败：" + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            problems.add("初始仓库构建失败：" + e.getMessage());
        } finally {
            sandboxManager.discard(sandbox.sessionId());
        }
        boolean ok = problems.isEmpty() && zeroStepFails && solutionPasses;
        return new LevelDraftDtos.SelfCheckResult(ok, true, zeroStepFails, solutionPasses, problems);
    }

    private MatchResult validate(SandboxRepo sandbox, LevelFile level) {
        GitGraph snapshot = graphMapper.map(sandbox);
        return goalMatcher.match(snapshot, level.goal(), path -> repoInspector.fileAtHead(sandbox, path));
    }

    /** 以 URL 上的 slug 为准回填 meta，并补齐可选缺省，避免前端漏填导致 NPE。 */
    private LevelFile normalize(LevelFile level, String slug) {
        if (level == null || level.meta() == null) {
            throw new CommandException("关卡内容不完整（缺少 meta）");
        }
        LevelFile.Meta m = level.meta();
        LevelFile.Meta meta = new LevelFile.Meta(
                slug,
                m.title(),
                m.description(),
                m.category(),
                m.difficulty(),
                m.mode() == null ? "solo" : m.mode(),
                m.orderIndex(),
                m.visibility() == null ? "public" : m.visibility());
        return new LevelFile(
                level.specVersion() == 0 ? 1 : level.specVersion(),
                meta, level.initial(), level.goal(), level.solution(), level.hints());
    }

    private Row requireOwned(Long userId, String slug) {
        requireLogin(userId);
        Row row = findRow(slug);
        if (row == null) {
            throw new CommandException("关卡不存在：" + slug);
        }
        if (!userId.equals(row.authorUserId)) {
            throw new CommandException("只能编辑自己创作的关卡");
        }
        return row;
    }

    private Row findRow(String slug) {
        List<Row> rows = jdbc.query(SELECT_ONE, this::mapRow, slug);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        LevelFile.Meta meta = new LevelFile.Meta(
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("category"),
                (int) rs.getShort("difficulty"),
                rs.getString("mode"),
                (Integer) rs.getObject("order_index"),
                rs.getString("visibility"));
        LevelFile level = new LevelFile(
                rs.getShort("schema_version"),
                meta,
                parse(rs.getString("initial_spec"), LevelFile.InitialSpec.class),
                parse(rs.getString("goal_spec"), LevelFile.GoalSpec.class),
                parse(rs.getString("solution_spec"), LevelFile.SolutionSpec.class),
                List.of());
        Row row = new Row();
        row.slug = rs.getString("slug");
        row.status = rs.getString("status");
        row.visibility = rs.getString("visibility");
        row.authorUserId = (Long) rs.getObject("author_user_id");
        row.level = level;
        return row;
    }

    private <T> T parse(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new LevelException("关卡 spec 解析失败：" + e.getOriginalMessage());
        }
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CommandException("关卡内容序列化失败");
        }
    }

    private void requireLogin(Long userId) {
        if (userId == null) {
            throw new CommandException("请先登录");
        }
    }

    private static final class Row {
        private String slug;
        private String status;
        private String visibility;
        private Long authorUserId;
        private LevelFile level;
    }
}
