package com.weixiao.merge;

import com.weixiao.utils.DiffUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * todo 添加测试
 * abc -> c <- b
 *
 * @author changzheng
 * @date 2026年03月30日 21:19
 */
@DisplayName("MergeEditPairing")
class MergeEditPairingTest {

    @Test
    @DisplayName("identical 文档与 merged：成对均为 EQL")
    void pair_identical_allPairedEql() {
        String m = "a\nb\nc\n";
        List<DiffUtils.Edit> left = DiffUtils.diff(m, m);
        List<DiffUtils.Edit> right = DiffUtils.diff(m, m);
        List<MergeEditPairing.PairedMergeEdit> pairs = MergeEditPairing.pairByMergedBLine(left, right);

        assertThat(pairs).isNotEmpty();
        assertThat(pairs).allMatch(p ->
                p.leftEdit() != null
                        && p.rightEdit() != null
                        && p.leftEdit().getType() == DiffUtils.EditType.EQL
                        && p.rightEdit().getType() == DiffUtils.EditType.EQL);
    }

    @Test
    @DisplayName("两侧同为删除且 a 侧行一致：直接合并为一对 DEL")
    void pair_symmetricDel_sameLineA_mergedAsOnePair() {
        String ours = "x\n";
        String theirs = "x\n";
        String merged = "";
        List<DiffUtils.Edit> left = DiffUtils.diff(ours, merged);
        List<DiffUtils.Edit> right = DiffUtils.diff(theirs, merged);

        List<MergeEditPairing.PairedMergeEdit> pairs = MergeEditPairing.pairByMergedBLine(left, right);

        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).leftEdit()).isNotNull();
        assertThat(pairs.get(0).rightEdit()).isNotNull();
        assertThat(pairs.get(0).leftEdit().getType()).isEqualTo(DiffUtils.EditType.DEL);
        assertThat(pairs.get(0).rightEdit().getType()).isEqualTo(DiffUtils.EditType.DEL);
        assertThat(pairs.get(0).leftEdit().getLineA().content())
                .isEqualTo(pairs.get(0).rightEdit().getLineA().content());
    }

    @Test
    @DisplayName("DEL 优先：两侧删除内容不同则分开展开，另一侧为 null")
    void pair_delPriority_differentLineA_otherNil() {
        List<DiffUtils.Edit> left = DiffUtils.diff("x\n", "");
        List<DiffUtils.Edit> right = DiffUtils.diff("y\n", "");

        List<MergeEditPairing.PairedMergeEdit> pairs = MergeEditPairing.pairByMergedBLine(left, right);

        assertThat(pairs).hasSize(2);
        assertThat(pairs.get(0).leftEdit()).isNotNull();
        assertThat(pairs.get(0).leftEdit().getType()).isEqualTo(DiffUtils.EditType.DEL);
        assertThat(pairs.get(0).rightEdit()).isNull();
        assertThat(pairs.get(1).leftEdit()).isNull();
        assertThat(pairs.get(1).rightEdit()).isNotNull();
        assertThat(pairs.get(1).rightEdit().getType()).isEqualTo(DiffUtils.EditType.DEL);
    }

    @Test
    @DisplayName("不同 left/right 相对同一 merged：可得到非空成对列表")
    void pair_differentSides_nonEmpty() {
        String merged = "a\n";
        List<DiffUtils.Edit> left = DiffUtils.diff("a\nb\n", merged);
        List<DiffUtils.Edit> right = DiffUtils.diff("a\nc\n", merged);

        List<MergeEditPairing.PairedMergeEdit> pairs = MergeEditPairing.pairByMergedBLine(left, right);

        assertThat(left).isNotEmpty();
        assertThat(right).isNotEmpty();
        assertThat(pairs).isNotEmpty();
    }
}
