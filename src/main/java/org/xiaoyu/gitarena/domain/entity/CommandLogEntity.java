package org.xiaoyu.gitarena.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 命令审计日志（database.md §5.6），只记录不参与命令执行。 */
@Data
@TableName("command_logs")
public class CommandLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long sandboxId;
    private Long roomId;
    private String rawInput;
    private String parsedCommand;
    private Boolean allowed;
    private Boolean success;
    private String stderrExcerpt;
    private Integer durationMs;
    private OffsetDateTime createdAt;
}
