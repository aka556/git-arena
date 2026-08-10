package org.xiaoyu.gitarena.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.domain.level.LevelFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 关卡来源解析：官方关卡来自 classpath（{@link LevelCatalog}），玩家创作的关卡来自 {@code levels} 表。
 *
 * <p>两者对引擎是同一种东西——都是 {@link LevelFile}，故构建/校验链路无需区分来源（§3 黄金法则的延伸：
 * 官方关卡与自定义关卡走同一套 LevelBuilder/GoalMatcher）。官方关卡优先：同 slug 时 classpath 覆盖库中行，
 * 避免自定义关卡顶替官方内容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LevelSource {

    private static final String SELECT_PUBLISHED = """
            SELECT slug, title, description, category, difficulty, order_index, mode,
                   initial_spec, goal_spec, solution_spec, schema_version
            FROM levels
            WHERE status = 'published' AND visibility = 'public'
              AND author_user_id IS NOT NULL AND deleted_at IS NULL
            ORDER BY category, order_index, id
            """;

    private static final String SELECT_ONE = """
            SELECT slug, title, description, category, difficulty, order_index, mode,
                   initial_spec, goal_spec, solution_spec, schema_version
            FROM levels
            WHERE slug = ? AND status = 'published' AND deleted_at IS NULL
            """;

    private final LevelCatalog catalog;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** 官方关卡 + 已发布的公开自定义关卡。 */
    public List<LevelFile> list() {
        List<LevelFile> all = new ArrayList<>(catalog.list());
        for (LevelFile custom : queryCustom(SELECT_PUBLISHED)) {
            if (!catalog.has(custom.meta().slug())) {
                all.add(custom);
            }
        }
        return all;
    }

    /** 按 slug 取关卡：官方优先，其次已发布的自定义关卡；都没有则抛 LevelException。 */
    public LevelFile get(String slug) {
        if (catalog.has(slug)) {
            return catalog.get(slug);
        }
        List<LevelFile> rows = queryCustom(SELECT_ONE, slug);
        if (rows.isEmpty()) {
            throw new LevelException("关卡不存在：" + slug);
        }
        return rows.get(0);
    }

    private List<LevelFile> queryCustom(String sql, Object... args) {
        try {
            return jdbc.query(sql, this::mapRow, args);
        } catch (RuntimeException e) {
            // 库不可用不该让官方关卡也打不开：自定义关卡降级为不可见
            log.warn("读取自定义关卡失败：{}", e.getMessage());
            return List.of();
        }
    }

    private LevelFile mapRow(ResultSet rs, int rowNum) throws SQLException {
        LevelFile.Meta meta = new LevelFile.Meta(
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("category"),
                (int) rs.getShort("difficulty"),
                rs.getString("mode"),
                (Integer) rs.getObject("order_index"),
                "public");
        return new LevelFile(
                rs.getShort("schema_version"),
                meta,
                parse(rs.getString("initial_spec"), LevelFile.InitialSpec.class),
                parse(rs.getString("goal_spec"), LevelFile.GoalSpec.class),
                parse(rs.getString("solution_spec"), LevelFile.SolutionSpec.class),
                List.of());
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
}
