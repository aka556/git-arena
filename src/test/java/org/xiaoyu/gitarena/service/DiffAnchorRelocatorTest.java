package org.xiaoyu.gitarena.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行级评论锚点重算（database.md §4.5）。
 *
 * <p>这是 PR 评审里最容易出错也最伤人的一环：错位的锚点会把评论挂到无关代码上，
 * 比丢失更具误导性。所以每种改动形态都单独立个用例，且"宁可判过时也不猜"是硬要求。
 */
class DiffAnchorRelocatorTest {

    private final DiffAnchorRelocator relocator = new DiffAnchorRelocator();

    /** 评论锚在新侧第 3 行（"    return a + b;"）。 */
    private static final String HUNK = """
            @@ -1,4 +1,5 @@
             public class Calc {
            +    // added
                 int add(int a, int b) {
                     return a + b;
                 }
            """;

    @Test
    void unchanged_file_keeps_the_same_line() {
        List<String> file = List.of(
                "public class Calc {",
                "    // added",
                "    int add(int a, int b) {",
                "        return a + b;",
                "    }");

        assertThat(relocator.relocate(HUNK, "new", 4, file)).hasValue(4);
    }

    @Test
    void lines_inserted_above_shift_the_anchor_down() {
        List<String> file = List.of(
                "package demo;",
                "",
                "public class Calc {",
                "    // added",
                "    int add(int a, int b) {",
                "        return a + b;",
                "    }");

        // 上方插了 2 行，锚点行内容不变 → 应重算到第 6 行，而不是死守 4
        assertThat(relocator.relocate(HUNK, "new", 4, file)).hasValue(6);
    }

    @Test
    void rewritten_anchor_line_is_reported_outdated_rather_than_guessed() {
        List<String> file = List.of(
                "public class Calc {",
                "    // added",
                "    int add(int a, int b) {",
                "        return Math.addExact(a, b);", // 锚点行被改写
                "    }");

        assertThat(relocator.relocate(HUNK, "new", 4, file)).isEmpty();
    }

    @Test
    void deleted_file_is_reported_outdated() {
        assertThat(relocator.relocate(HUNK, "new", 4, null)).isEmpty();
    }

    @Test
    void ambiguous_duplicate_lines_are_disambiguated_by_context() {
        String hunk = """
                @@ -1,6 +1,6 @@
                 void a() {
                     log("x");
                 }
                 void b() {
                     log("x");
                 }
                """;
        List<String> file = List.of(
                "// header",
                "void a() {",
                "    log(\"x\");",
                "}",
                "void b() {",
                "    log(\"x\");",
                "}");

        // 两处 log("x") 完全相同，靠上下文（前一行是 void b() {）选中第二处
        assertThat(relocator.relocate(hunk, "new", 5, file)).hasValue(6);
    }

    @Test
    void stable_position_wins_even_when_content_repeats() {
        String hunk = """
                @@ -1,3 +1,3 @@
                 x
                 x
                 x
                """;
        List<String> file = List.of("x", "x", "x", "x", "x", "x");

        // 原位置内容仍是 x：没有任何证据表明它移动过，保持原行号才是最合理的答案
        assertThat(relocator.relocate(hunk, "new", 2, file)).hasValue(2);
    }

    @Test
    void indistinguishable_duplicates_refuse_to_guess() {
        String hunk = """
                @@ -1,3 +1,3 @@
                 x
                 x
                 x
                """;
        // 原位置(第2行)已不是 x，必须搜索；x 出现在 3、5 两处且上下文完全对称
        List<String> file = List.of("q", "q", "x", "q", "x", "q");

        assertThat(relocator.relocate(hunk, "new", 2, file)).isEmpty();
    }

    @Test
    void old_side_anchor_uses_deletion_lines() {
        String hunk = """
                @@ -10,4 +10,3 @@
                 keep
                -removed line
                 tail
                """;
        List<String> baseFile = List.of(
                "l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "l9",
                "keep", "removed line", "tail");

        // old 侧第 11 行 = "removed line"，在基线版本里仍然存在
        assertThat(relocator.relocate(hunk, "old", 11, baseFile)).hasValue(11);
    }

    @Test
    void malformed_or_missing_anchor_input_is_empty_not_an_exception() {
        assertThat(relocator.relocate(null, "new", 4, List.of("a"))).isEmpty();
        assertThat(relocator.relocate(HUNK, "new", null, List.of("a"))).isEmpty();
        assertThat(relocator.relocate("no hunk header here", "new", 4, List.of("a"))).isEmpty();
        assertThat(relocator.relocate(HUNK, "new", 999, List.of("a"))).isEmpty();
        assertThat(relocator.relocate(HUNK, "new", 4, List.of())).isEmpty();
    }

    @Test
    void relocate_never_returns_a_line_outside_the_file() {
        List<String> file = List.of("public class Calc {", "    // added");

        OptionalInt result = relocator.relocate(HUNK, "new", 4, file);

        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).isEmpty(),
                r -> assertThat(r.getAsInt()).isBetween(1, file.size()));
    }
}
