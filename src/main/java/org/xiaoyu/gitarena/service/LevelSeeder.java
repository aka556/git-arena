package org.xiaoyu.gitarena.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.domain.entity.LevelHintEntity;
import org.xiaoyu.gitarena.domain.level.LevelFile;
import org.xiaoyu.gitarena.mapper.LevelHintMapper;

/**
 * 启动时把 classpath 关卡 seed 进 levels 表（P1：进度落库需要 level 外键存在，database.md §3.4）。
 * 按 slug upsert（幂等，引擎升级重启不重复插）；status 直接置 published（官方关卡即上架）。
 *
 * <p>jsonb 列以 JSON 字符串写入——依赖 JDBC url 的 {@code stringtype=unspecified}，无需类型处理器。
 * ApplicationRunner 在上下文就绪后、真实流量前执行，故 LevelRegistry 映射先于进度写入建立。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LevelSeeder implements ApplicationRunner {

    // 部分唯一索引 levels_slug_uq 带 WHERE deleted_at IS NULL，ON CONFLICT 须带同谓词以推断该索引
    private static final String UPSERT = """
            INSERT INTO levels (slug, title, description, category, difficulty, order_index, mode,
                                initial_spec, goal_spec, solution_spec, visibility, status, schema_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, 'public', 'published', ?)
            ON CONFLICT (slug) WHERE deleted_at IS NULL
            DO UPDATE SET title = EXCLUDED.title, description = EXCLUDED.description,
                          category = EXCLUDED.category, difficulty = EXCLUDED.difficulty,
                          order_index = EXCLUDED.order_index, mode = EXCLUDED.mode,
                          initial_spec = EXCLUDED.initial_spec, goal_spec = EXCLUDED.goal_spec,
                          solution_spec = EXCLUDED.solution_spec, status = 'published',
                          schema_version = EXCLUDED.schema_version, updated_at = now()
            RETURNING id
            """;

    private final LevelCatalog catalog;
    private final LevelRegistry registry;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LevelHintMapper levelHintMapper;

    @Override
    public void run(ApplicationArguments args) {
        int seeded = 0;
        for (LevelFile level : catalog.list()) {
            LevelFile.Meta m = level.meta();
            Long id = jdbc.queryForObject(UPSERT, Long.class,
                    m.slug(), m.title(), m.description(), m.category(), m.difficulty(),
                    m.orderIndex(), m.mode(),
                    json(level.initial()), json(level.goal()), json(level.solution()),
                    level.specVersion());
            registry.register(m.slug(), id);
            seedHints(id, level.hints());
            seeded++;
        }
        log.info("已 seed {} 个关卡到 levels 表（进度落库外键就绪）", seeded);
    }

    /**
     * 把关卡文件的 hints 拆行写入 level_hints（database.md §5.4）。先删后插保证幂等——
     * 引擎升级重启时提示内容以文件为准整体刷新，不残留旧行。
     */
    private void seedHints(Long levelId, java.util.List<LevelFile.Hint> hints) {
        levelHintMapper.delete(new LambdaQueryWrapper<LevelHintEntity>()
                .eq(LevelHintEntity::getLevelId, levelId));
        if (hints == null) {
            return;
        }
        int order = 0;
        for (LevelFile.Hint h : hints) {
            LevelHintEntity row = new LevelHintEntity();
            row.setLevelId(levelId);
            row.setOrderIndex(order++);
            row.setTier(h.tier() == null ? 1 : h.tier());
            row.setBody(h.body());
            row.setCostPoints(h.costPoints() == null ? 0 : h.costPoints());
            levelHintMapper.insert(row);
        }
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("关卡 spec 序列化失败", e);
        }
    }
}
