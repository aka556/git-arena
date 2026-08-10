package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 分级提示（database.md §5.4，落库到 level_hints 表）。唯一约束 (level_id, order_index)。
 * 提示内容由关卡文件（LevelFile.hints）在 seed 时拆行写入，供提示系统与扣分联动。
 * <p>V1 迁移中该表无 created_at/updated_at 列，实体不映射时间字段。
 */
@Data
@TableName("level_hints")
public class LevelHintEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long levelId;
    /** 提示顺序（分级，越后越直接）。 */
    private Integer orderIndex;
    /** 提示层级 1..n（1=最含蓄）。 */
    private Integer tier;
    private String body;
    /** 关卡文件映射的历史字段；使用提示已不扣分（2026-08-09 起），不再写 hint_penalty 流水。 */
    private Integer costPoints;
}
