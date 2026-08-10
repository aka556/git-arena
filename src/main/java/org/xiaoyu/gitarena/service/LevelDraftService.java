package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.LevelDraftDtos;

import java.util.List;

/**
 * 关卡编辑器服务（M4，database.md §3.3 的 P2 编辑器工作流）。
 *
 * <p>关卡是创作内容：作者保存草稿 → 跑自证闭环（docs/level-spec.md §7）→ 通过才可发布。
 * 发布后的公开关卡经 {@link LevelSource} 与官方关卡一起进入关卡列表，走同一套构建/校验链路。
 *
 * <p>鉴权：一律以登录用户为准，只能读写自己创作的关卡；官方关卡（author_user_id 为空）不可被编辑器改动。
 */
public interface LevelDraftService {

    /** 我创作的关卡（草稿 + 已发布）。 */
    List<LevelDraftDtos.DraftSummary> listMine(Long userId);

    LevelDraftDtos.DraftDetail get(Long userId, String slug);

    /** 保存草稿（按 slug upsert）；仅做语义校验，自证闭环留到发布时（允许中途保存半成品）。 */
    LevelDraftDtos.DraftDetail save(Long userId, LevelDraftDtos.SaveRequest request);

    /** 跑自证闭环但不改状态——编辑器的"试跑"按钮。 */
    LevelDraftDtos.SelfCheckResult selfCheck(Long userId, String slug);

    /** 发布：自证闭环全绿才置 published，否则连同问题一起拒绝（fail-closed）。 */
    LevelDraftDtos.SelfCheckResult publish(Long userId, String slug);

    /** 撤回到草稿状态（已发布关卡下架）。 */
    void unpublish(Long userId, String slug);

    /** 软删除（database.md §3.3 deleted_at，草稿可恢复语义）。 */
    void delete(Long userId, String slug);
}
