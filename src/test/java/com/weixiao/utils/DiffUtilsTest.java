package com.weixiao.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DiffUtils (Myers diff) 测试")
class DiffUtilsTest {

    private static List<String> lines(String... s) {
        return Arrays.asList(s);
    }

    private static List<DiffUtils.Line> nl(List<String> contents) {
        return DiffUtils.numberedLines(contents);
    }

    @Test
    @DisplayName("两段相同文本 diff 结果全为 EQL")
    void diff_identical_returnsAllEql() {
        List<String> a = lines("a\n", "b\n", "c\n");
        List<String> b = lines("a\n", "b\n", "c\n");
        List<DiffUtils.Edit> result = DiffUtils.diffLines(nl(a), nl(b));
        assertThat(result).hasSize(3);
        assertThat(result).allMatch(e -> e.getType() == DiffUtils.EditType.EQL);
        assertThat(result.get(0).getLine()).isEqualTo("a\n");
        assertThat(result.get(1).getLine()).isEqualTo("b\n");
        assertThat(result.get(2).getLine()).isEqualTo("c\n");
    }

    @Test
    @DisplayName("开头删除一行：第一行为 DEL")
    void diff_deletionAtStart() {
        List<String> doc = lines("the\n", "quick\n", "brown\n", "fox\n", "jumps\n", "over\n", "the\n", "lazy\n", "dog\n");
        List<String> changed = lines("quick\n", "brown\n", "fox\n", "jumps\n", "over\n", "the\n", "lazy\n", "dog\n");
        List<DiffUtils.Edit> result = DiffUtils.diffLines(nl(doc), nl(changed));
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getType()).isEqualTo(DiffUtils.EditType.DEL);
        assertThat(result.get(0).getLine()).isEqualTo("the\n");
    }

    @Test
    @DisplayName("开头插入一行：第一行为 INS")
    void diff_insertionAtStart() {
        List<String> doc = lines("the\n", "quick\n", "brown\n", "fox\n");
        List<String> changed = lines("so\n", "the\n", "quick\n", "brown\n", "fox\n");
        List<DiffUtils.Edit> result = DiffUtils.diffLines(nl(doc), nl(changed));
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getType()).isEqualTo(DiffUtils.EditType.INS);
        assertThat(result.get(0).getLine()).isEqualTo("so\n");
    }

    @Test
    @DisplayName("中间替换：出现 DEL 和 INS")
    void diff_changeInMiddle() {
        List<String> doc = lines("the\n", "quick\n", "brown\n", "fox\n", "jumps\n", "over\n", "the\n", "lazy\n", "dog\n");
        List<String> changed = lines("the\n", "quick\n", "brown\n", "fox\n", "leaps\n", "right\n", "over\n", "the\n", "lazy\n", "dog\n");
        List<DiffUtils.Edit> result = DiffUtils.diffLines(nl(doc), nl(changed));
        List<DiffUtils.Edit> delAndIns = result.stream()
                .filter(e -> e.getType() == DiffUtils.EditType.DEL || e.getType() == DiffUtils.EditType.INS)
                .collect(Collectors.toList());
        assertThat(delAndIns).anyMatch(e -> e.getType() == DiffUtils.EditType.DEL && e.getLine().equals("jumps\n"));
        assertThat(delAndIns).anyMatch(e -> e.getType() == DiffUtils.EditType.INS && e.getLine().equals("leaps\n"));
        assertThat(delAndIns).anyMatch(e -> e.getType() == DiffUtils.EditType.INS && e.getLine().equals("right\n"));
    }

    @Test
    @DisplayName("多处修改：开头和结尾都有变化")
    void diff_multipleChanges() {
        List<String> doc = lines("the\n", "quick\n", "brown\n", "fox\n", "jumps\n", "over\n", "the\n", "lazy\n", "dog\n");
        List<String> changed = lines("the\n", "brown\n", "fox\n", "jumps\n", "over\n", "the\n", "lazy\n", "cat\n");
        List<DiffUtils.Edit> result = DiffUtils.diffLines(nl(doc), nl(changed));
        assertThat(result).anyMatch(e -> e.getType() == DiffUtils.EditType.DEL && e.getLine().equals("quick\n"));
        assertThat(result).anyMatch(e -> e.getType() == DiffUtils.EditType.DEL && e.getLine().equals("dog\n"));
        assertThat(result).anyMatch(e -> e.getType() == DiffUtils.EditType.INS && e.getLine().equals("cat\n"));
    }

    @Test
    @DisplayName("String 入参：按行拆分后 diff")
    void diff_stringInput_splitsByLines() {
        String a = "a\nb\nc\n";
        String b = "a\nb\nc\nd\n";
        List<DiffUtils.Edit> result = DiffUtils.diff(a, b);
        assertThat(result).hasSize(4);
        assertThat(result.get(0).getType()).isEqualTo(DiffUtils.EditType.EQL);
        assertThat(result.get(1).getType()).isEqualTo(DiffUtils.EditType.EQL);
        assertThat(result.get(2).getType()).isEqualTo(DiffUtils.EditType.EQL);
        assertThat(result.get(3).getType()).isEqualTo(DiffUtils.EditType.INS);
        assertThat(result.get(3).getLine()).isEqualTo("d\n");
    }

    @Test
    @DisplayName("空 a：全为 INS")
    void diff_emptyA_allIns() {
        List<String> a = lines();
        List<String> b = lines("x\n", "y\n");
        List<DiffUtils.Edit> result = DiffUtils.diffLines(nl(a), nl(b));
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> e.getType() == DiffUtils.EditType.INS);
    }

    @Test
    @DisplayName("空 b：全为 DEL")
    void diff_emptyB_allDel() {
        List<String> a = lines("x\n", "y\n");
        List<String> b = lines();
        List<DiffUtils.Edit> result = DiffUtils.diffLines(nl(a), nl(b));
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> e.getType() == DiffUtils.EditType.DEL);
    }

    @Test
    @DisplayName("Edit.toString 格式：空格/+/− 加行内容")
    void edit_toString_format() {
        assertThat(new DiffUtils.Edit(DiffUtils.EditType.EQL, new DiffUtils.Line(0, "a\n")).toString()).isEqualTo(" a\n");
        assertThat(new DiffUtils.Edit(DiffUtils.EditType.INS, new DiffUtils.Line(1, "b\n")).toString()).isEqualTo("+b\n");
        assertThat(new DiffUtils.Edit(DiffUtils.EditType.DEL, new DiffUtils.Line(2, "c\n")).toString()).isEqualTo("-c\n");
    }

    @Test
    @DisplayName("两段都为空：结果为空列表")
    void diff_bothEmpty_returnsEmpty() {
        List<DiffUtils.Edit> result = DiffUtils.diffLines(nl(lines()), nl(lines()));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("测试一个比较复杂点的场景")
    void diffStr() {
        List<String> a = Arrays.asList("ABCABBA".split(""));
        List<String> b = Arrays.asList("CBABAC".split(""));
        List<DiffUtils.Edit> diffs = DiffUtils.diff(a, b);
        for (DiffUtils.Edit edit : diffs) {
            System.out.println(edit);
        }
    }

    @Test
    @DisplayName("测试其中一个为空")
    void diffStrEmpty() {
        List<String> a = Arrays.asList("ABCABBA".split(""));
        List<String> b = List.of();
        List<DiffUtils.Edit> diffs = DiffUtils.diff(a, b);
        for (DiffUtils.Edit edit : diffs) {
            System.out.println(edit);
        }
    }
}
