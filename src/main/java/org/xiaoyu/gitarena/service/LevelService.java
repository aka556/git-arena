package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.LevelDetail;
import org.xiaoyu.gitarena.domain.dto.LevelSummary;
import org.xiaoyu.gitarena.domain.dto.StartLevelResponse;
import org.xiaoyu.gitarena.domain.dto.ValidateResponse;

import java.util.List;

/**
 * 关卡系统 v1（P0 关卡系统）：列表、详情、开始、校验。
 */
public interface LevelService {

    List<LevelSummary> list();

    LevelDetail detail(String slug);

    /** 新建沙盒并把关卡 initial 构建进去，返回当前图 + 目标图。 */
    StartLevelResponse start(String slug);

    /** 校验当前会话沙盒是否达成关卡目标。 */
    ValidateResponse validate(String sessionId, String slug);
}
