package org.xiaoyu.gitarena.service;

import org.xiaoyu.gitarena.domain.dto.CommandResponse;

/**
 * 命令执行服务——图形操作与终端输入的<b>唯一执行链路</b>（CLAUDE.md §3 黄金法则）。
 * 图形面板的按钮动作必须封成等价命令字符串走此方法，绝不另开状态更新路径。
 */
public interface CommandService {

    CommandResponse execute(String sessionId, String rawCommand);
}
