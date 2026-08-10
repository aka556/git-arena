package org.xiaoyu.gitarena.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.xiaoyu.gitarena.domain.level.LevelFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关卡目录：启动时从 classpath {@code levels/*.level.json} 加载官方关卡，Jackson 绑定形状
 * （FAIL_ON_UNKNOWN_PROPERTIES：多字段即拒，近似 schema 的 additionalProperties:false），
 * 再经 {@link LevelValidator} 语义校验。任一关卡不合契约则启动失败（fail-closed，§0）。
 *
 * <p>M2 v1：关卡是 classpath 内的静态内容，不写库——进度持久化需 P1 用户体系（database.md §3.4）。
 */
@Slf4j
@Component
public class LevelCatalog {

    private static final String PATTERN = "classpath:levels/*.level.json";

    private final ObjectMapper mapper;
    private final LevelValidator validator;
    private final Map<String, LevelFile> bySlug = new LinkedHashMap<>();

    public LevelCatalog(ObjectMapper objectMapper, LevelValidator validator) {
        // 关卡形状严格：未知字段视为契约违背
        this.mapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.validator = validator;
    }

    @PostConstruct
    void load() {
        List<LevelFile> levels = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
            for (Resource resource : resources) {
                LevelFile level = parse(resource);
                validator.validate(level);
                levels.add(level);
            }
        } catch (IOException e) {
            throw new LevelException("扫描关卡资源失败：" + e.getMessage());
        }

        levels.sort(Comparator
                .comparing((LevelFile l) -> l.meta().category())
                .thenComparing(l -> l.meta().orderIndex() == null ? Integer.MAX_VALUE : l.meta().orderIndex())
                .thenComparing(l -> l.meta().slug()));
        for (LevelFile level : levels) {
            if (bySlug.putIfAbsent(level.meta().slug(), level) != null) {
                throw new LevelException("关卡 slug 重复：" + level.meta().slug());
            }
        }
        log.info("已加载 {} 个关卡：{}", bySlug.size(), bySlug.keySet());
    }

    private LevelFile parse(Resource resource) {
        try {
            return mapper.readValue(resource.getInputStream(), LevelFile.class);
        } catch (IOException e) {
            throw new LevelException("解析关卡文件失败 " + resource.getFilename() + "：" + e.getMessage());
        }
    }

    public List<LevelFile> list() {
        return List.copyOf(bySlug.values());
    }

    /** 该 slug 是否为官方关卡（自定义关卡不得顶替官方内容，见 LevelSource）。 */
    public boolean has(String slug) {
        return bySlug.containsKey(slug);
    }

    public LevelFile get(String slug) {
        LevelFile level = bySlug.get(slug);
        if (level == null) {
            throw new LevelException("关卡不存在：" + slug);
        }
        return level;
    }
}
