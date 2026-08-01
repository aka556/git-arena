package org.xiaoyu.gitarena.domain.dto;

import java.util.List;

/**
 * 关卡校验结果（POST /api/levels/{slug}/validate）。passed=false 时 reasons 列出结构化差异
 * （缺哪个引用、哪个节点父不符…），供前端提示"还差什么"（level-spec.md §5.3）。
 */
public record ValidateResponse(boolean passed, List<String> reasons) {}
