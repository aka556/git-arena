package org.xiaoyu.gitarena.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 启动时幂等导入官方成就定义。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AchievementSeeder implements ApplicationRunner {

    private static final String UPSERT = """
            INSERT INTO achievements (code, name, description, icon, points, category, criteria, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, true)
            ON CONFLICT (code)
            DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
                          icon = EXCLUDED.icon, points = EXCLUDED.points,
                          category = EXCLUDED.category, criteria = EXCLUDED.criteria,
                          is_active = true, updated_at = now()
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        List<Definition> definitions = List.of(
                new Definition("first_commit", "第一笔提交", "完成你的第一次提交。", "commit", 5, "solo", "commit"),
                new Definition("first_level", "初出茅庐", "首次完成一个关卡。", "level", 10, "solo", "level_complete"),
                new Definition("first_merge", "合并协作者", "首次成功合并一个 Pull Request。", "merge", 15, "collab", "pr_merged"),
                new Definition("conflict_slayer", "冲突终结者", "完成一个冲突类关卡。", "conflict", 20, "special", "conflict_level_complete")
        );
        for (Definition definition : definitions) {
            jdbc.update(UPSERT,
                    definition.code(), definition.name(), definition.description(), definition.icon(),
                    definition.points(), definition.category(), json(definition.criteria()));
        }
        log.info("已 seed {} 个官方成就", definitions.size());
    }

    private String json(String event) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("event", event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("成就条件序列化失败", e);
        }
    }

    private record Definition(String code, String name, String description, String icon,
                              int points, String category, String criteria) {
    }
}
