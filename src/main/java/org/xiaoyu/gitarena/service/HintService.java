package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.HintDtos;

/** 分级提示与使用扣分服务。 */
public interface HintService {

    HintDtos.UseResponse use(Long userId, String slug, Long hintId);
}
