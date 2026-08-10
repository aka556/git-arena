package org.xiaoyu.gitarena.domain.dto;

import jakarta.validation.constraints.NotBlank;
import org.xiaoyu.gitarena.domain.level.LevelFile;

import java.time.OffsetDateTime;
import java.util.List;

/** 关卡编辑器（M4）DTO：草稿保存/列表/自证结果。 */
public final class LevelDraftDtos {

    private LevelDraftDtos() {
    }

    /** 保存草稿：整份 LevelFile 提交（编辑器即"可视化的 level.json 编辑"）。 */
    public record SaveRequest(
            @NotBlank(message = "关卡内容不能为空") String slug,
            LevelFile level
    ) {}

    /** 我的草稿/已发布关卡条目。 */
    public record DraftSummary(
            String slug,
            String title,
            String category,
            int difficulty,
            String mode,
            String status,
            String visibility,
            OffsetDateTime updatedAt
    ) {}

    /** 草稿详情：回填编辑器。 */
    public record DraftDetail(
            String slug,
            String status,
            String visibility,
            LevelFile level
    ) {}

    /**
     * 自证闭环结果（docs/level-spec.md §7）：语义校验 + 零步不通关 + 参考解通关。
     * 三项全绿才允许发布；不绿时 problems 逐条说明，供编辑器直接展示。
     */
    public record SelfCheckResult(
            boolean ok,
            boolean semanticsOk,
            boolean zeroStepFails,
            boolean solutionPasses,
            List<String> problems
    ) {}
}
