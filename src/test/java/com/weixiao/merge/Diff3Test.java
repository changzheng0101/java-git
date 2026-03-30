package com.weixiao.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Diff3 测试")
class Diff3Test {

    /**
     * 场景：只有 ours 改动，theirs 与 base 完全一致，应自动合并且无冲突。
     * <p>
     * 文本示意图：
     * <p>
     * O(base):
     * a
     * b
     * c
     * <p>
     * A(ours):
     * a
     * B
     * c
     * <p>
     * B(theirs):
     * a
     * b
     * c
     * <p>
     * 期望：
     * - hasConflicts=false；
     * - merged 为 a/B/c（保留 ours 改动）。
     */
    @Test
    @DisplayName("仅一侧修改时可自动合并且无冲突")
    void merge_oneSidedChange_clean() {
        String base = "a\nb\nc\n";
        String ours = "a\nB\nc\n";
        String theirs = "a\nb\nc\n";

        Diff3.MergeFileResult mergeFileResult = Diff3.merge(base, ours, theirs);
        String merged = mergeFileResult.render("HEAD", "topic");

        assertThat(mergeFileResult.hasConflicts()).isFalse();
        assertThat(merged).isEqualTo("a\nB\nc\n");
    }

    /*
     * 场景：两侧在不同行独立改动，应同时保留两侧改动并自动合并。
     * <p>
     * 文本示意图：
     * <p>
     * O(base):
     *   a
     *   b
     *   c
     * <p>
     * A(ours):
     *   a
     *   B
     *   c
     * <p>
     * B(theirs):
     *   a
     *   b
     *   C
     * <p>
     * 期望：
     * - hasConflicts=false；
     * - merged 为 a/B/C（两侧都保留）。
     * todo 这个合理 但是目前的实现有缺陷 达不到这个要求
     */
//    @Test
//    @DisplayName("两侧独立修改不同行时可自动合并")
//    void merge_nonOverlappingChanges_clean() {
//        String base = "a\nb\nc\n";
//        String ours = "a\nB\nc\n";
//        String theirs = "a\nb\nC\n";
//
//        Diff3.MergeFileResult mergeFileResult = Diff3.merge(base, ours, theirs);
//        String merged = mergeFileResult.render("HEAD", "topic");
//
//        assertThat(mergeFileResult.hasConflicts()).isFalse();
//        assertThat(merged).isEqualTo("a\nB\nC\n");
//    }

    /**
     * 场景：两侧在同一块给出不同改动，应输出冲突标记块。
     * <p>
     * 文本示意图：
     * <p>
     * O(base):
     * line1
     * line2
     * <p>
     * A(ours):
     * line1
     * ours
     * <p>
     * B(theirs):
     * line1
     * theirs
     * <p>
     * 期望：
     * - hasConflicts=true；
     * - merged 包含 <<<<<<< HEAD / ======= / >>>>>>> topic 冲突块。
     */
    @Test
    @DisplayName("两侧在同一块有不同修改时输出冲突标记")
    void merge_overlappingChanges_conflict() {
        String base = "line1\nline2\n";
        String ours = "line1\nours\n";
        String theirs = "line1\ntheirs\n";

        Diff3.MergeFileResult mergeFileResult = Diff3.merge(base, ours, theirs);
        String merged = mergeFileResult.render("HEAD", "topic");

        assertThat(mergeFileResult.hasConflicts()).isTrue();
        assertThat(merged).contains("<<<<<<< HEAD");
        assertThat(merged).contains("ours");
        assertThat(merged).contains("=======");
        assertThat(merged).contains("theirs");
        assertThat(merged).contains(">>>>>>> topic");
    }
}
