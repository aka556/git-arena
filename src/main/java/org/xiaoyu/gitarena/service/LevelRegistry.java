package org.xiaoyu.gitarena.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关卡 slug → 数据库 levels.id 映射。关卡内容由 classpath JSON 驱动引擎（构建/校验），
 * 但进度落库需要 user_level_progress.level_id 外键指向 levels 表，故启动时把关卡 seed 进库（LevelSeeder）
 * 并在此登记映射，供进度写入取 level_id。
 */
@Component
public class LevelRegistry {

    private final Map<String, Long> idBySlug = new ConcurrentHashMap<>();
    private final Map<Long, String> slugById = new ConcurrentHashMap<>();

    public void register(String slug, Long id) {
        idBySlug.put(slug, id);
        slugById.put(id, slug);
    }

    /** 取 level_id；未 seed 的 slug 返回 null（调用方据此决定是否落库）。 */
    public Long idOf(String slug) {
        return idBySlug.get(slug);
    }

    /** 反查 slug（进度回读时把 level_id 还原为对外稳定的 slug）；未知返回 null。 */
    public String slugOf(Long id) {
        return slugById.get(id);
    }
}
