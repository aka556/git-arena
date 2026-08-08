package org.xiaoyu.gitarena.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行级评论的锚点重算（database.md §4.5「重算职责在后端」）。
 *
 * <p><b>为什么需要</b>：评论写在「文件 X 第 12 行」上，源分支再推一次提交，第 12 行可能已经是别的内容了。
 * 裸行号会静默错位——评论挂到无关代码上比丢失更糟。所以真相存的是<b>写评论那一刻的快照</b>
 * （{@code anchor_commit_sha} + {@code original_line} + {@code diff_hunk}），
 * 由本类在每次源分支更新后，用 hunk 里的上下文把行号<b>重新算</b>到新 HEAD。
 *
 * <p><b>本类是纯函数</b>：不碰 JGit、不碰库，输入行文本、输出新行号，因而可以直接对着各种改动形态写测试。
 * 匹配不上就返回空——由调用方置 {@code is_outdated=true}，前端标注「评论已过时」。
 * 宁可诚实地说"定位不了"，也不要猜一个行号。
 */
@Component
public class DiffAnchorRelocator {

    /** 上下文窗口：锚点行前后各取几行参与打分。太小易误判，太大则一点点改动就判过时。 */
    private static final int CONTEXT = 3;

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    /**
     * 把锚点行重定位到新版本文件里。
     *
     * @param diffHunk     写评论时定格的 hunk 文本（含 {@code @@} 头）
     * @param side         锚在 diff 的哪一侧：{@code old} / {@code new}
     * @param originalLine 该侧的原始行号
     * @param newFileLines 目标版本的文件全文（按行）；null 表示文件已不存在
     * @return 新行号（1 基）；无法可信定位时为空
     */
    public OptionalInt relocate(String diffHunk, String side, Integer originalLine, List<String> newFileLines) {
        if (diffHunk == null || originalLine == null || newFileLines == null || newFileLines.isEmpty()) {
            return OptionalInt.empty();
        }
        List<String> sideLines = sideLines(diffHunk, side);
        int anchorIndex = indexOfLine(diffHunk, side, originalLine);
        if (anchorIndex < 0 || anchorIndex >= sideLines.size()) {
            return OptionalInt.empty();
        }
        String anchor = sideLines.get(anchorIndex);

        // 行号未变且内容仍吻合：最常见的情形（改动发生在别处），直接确认，省去搜索。
        if (originalLine <= newFileLines.size() && newFileLines.get(originalLine - 1).equals(anchor)) {
            return OptionalInt.of(originalLine);
        }

        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < newFileLines.size(); i++) {
            if (newFileLines.get(i).equals(anchor)) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            return OptionalInt.empty(); // 锚点行本身没了：改写或删除，如实标过时
        }
        if (candidates.size() == 1) {
            return OptionalInt.of(candidates.get(0) + 1);
        }

        // 同样内容出现多次（空行、`}`、重复样板）：靠上下文打分消歧，仍并列则拒绝猜测。
        int bestScore = -1;
        int bestIndex = -1;
        boolean tie = false;
        for (int candidate : candidates) {
            int score = contextScore(sideLines, anchorIndex, newFileLines, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = candidate;
                tie = false;
            } else if (score == bestScore) {
                tie = true;
            }
        }
        if (tie || bestScore <= 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(bestIndex + 1);
    }

    /** 上下文吻合度：锚点行前后各 CONTEXT 行逐一比对，命中一行记一分。 */
    private int contextScore(List<String> hunkLines, int anchorIndex, List<String> fileLines, int candidate) {
        int score = 0;
        for (int offset = -CONTEXT; offset <= CONTEXT; offset++) {
            if (offset == 0) {
                continue;
            }
            int hunkPos = anchorIndex + offset;
            int filePos = candidate + offset;
            if (hunkPos < 0 || hunkPos >= hunkLines.size() || filePos < 0 || filePos >= fileLines.size()) {
                continue;
            }
            if (hunkLines.get(hunkPos).equals(fileLines.get(filePos))) {
                score++;
            }
        }
        return score;
    }

    /**
     * 抽出 hunk 中属于指定侧的行内容（按该侧顺序）。
     * old 侧取 context + 删除行，new 侧取 context + 新增行——正是该侧文件当时的真实样子。
     */
    private List<String> sideLines(String diffHunk, String side) {
        boolean wantNew = !PrSide.OLD.equals(side);
        List<String> result = new ArrayList<>();
        boolean inHunk = false;
        for (String raw : diffHunk.split("\n", -1)) {
            if (raw.startsWith("@@")) {
                inHunk = true;
                continue;
            }
            if (!inHunk || raw.isEmpty()) {
                continue;
            }
            char marker = raw.charAt(0);
            String content = raw.substring(1);
            if (marker == ' ') {
                result.add(content);
            } else if (marker == '+' && wantNew) {
                result.add(content);
            } else if (marker == '-' && !wantNew) {
                result.add(content);
            }
        }
        return result;
    }

    /** 目标行号在该侧行序列中的下标（hunk 头声明了该侧起始行号，逐行推进即可对上）。 */
    private int indexOfLine(String diffHunk, String side, int originalLine) {
        boolean wantNew = !PrSide.OLD.equals(side);
        int start = -1;
        for (String raw : diffHunk.split("\n", -1)) {
            Matcher matcher = HUNK_HEADER.matcher(raw);
            if (matcher.find()) {
                start = Integer.parseInt(matcher.group(wantNew ? 2 : 1));
                break;
            }
        }
        if (start < 0) {
            return -1;
        }
        return originalLine - start;
    }

    /** 侧别常量，避免和实体类互相依赖。 */
    public static final class PrSide {
        public static final String OLD = "old";
        public static final String NEW = "new";

        private PrSide() {
        }
    }
}
