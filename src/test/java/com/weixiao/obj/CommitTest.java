package com.weixiao.obj;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Commit 工具方法测试")
class CommitTest {

    private static final String AUTHOR_FULL = "user <user@local> 1700000000 +0000";

    @Test
    @DisplayName("getAuthorTimestamp 从 author 字符串解析出 epoch 秒")
    void getAuthorTimestamp_parsesEpochSeconds() {
        assertThat(Commit.getAuthorTimestamp(AUTHOR_FULL)).isEqualTo(1700000000L);
        assertThat(Commit.getAuthorTimestamp("Alice <a@b.com> 1234567890 -0800")).isEqualTo(1234567890L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("getAuthorTimestamp null 或空返回 0")
    void getAuthorTimestamp_nullOrEmpty_returnsZero(String author) {
        assertThat(Commit.getAuthorTimestamp(author)).isEqualTo(0L);
    }

    @Test
    @DisplayName("getAuthorTimestamp 无数字时返回 0")
    void getAuthorTimestamp_noDigits_returnsZero() {
        assertThat(Commit.getAuthorTimestamp("Name <email>")).isEqualTo(0L);
    }

    @Test
    @DisplayName("formatAuthorNameEmail 解析出 Name <email> 部分")
    void formatAuthorNameEmail_extractsNameAndEmail() {
        assertThat(Commit.formatAuthorNameEmail(AUTHOR_FULL)).isEqualTo("user <user@local>");
        assertThat(Commit.formatAuthorNameEmail("Alice Bob <a@b.com> 1234567890 +0000"))
                .isEqualTo("Alice Bob <a@b.com>");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("formatAuthorNameEmail null 或空返回空串")
    void formatAuthorNameEmail_nullOrEmpty_returnsEmpty(String author) {
        assertThat(Commit.formatAuthorNameEmail(author)).isEmpty();
    }

    @Test
    @DisplayName("formatAuthorDate 格式化为 Git 风格日期")
    void formatAuthorDate_formatsLikeGit() {
        String s = Commit.formatAuthorDate(AUTHOR_FULL);
        assertThat(s).contains("2023").matches(".*\\d{2}:\\d{2}:\\d{2}.*");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("formatAuthorDate null 或空返回空串")
    void formatAuthorDate_nullOrEmpty_returnsEmpty(String author) {
        assertThat(Commit.formatAuthorDate(author)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "only line, only line",
            "'first\nsecond', first",
            "'  trim  ', trim",
            "'a\n\nb', a"
    })
    @DisplayName("firstLine 取首行或整段")
    void firstLine_returnsFirstLineOrTrimmed(String input, String expected) {
        assertThat(Commit.firstLine(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("firstLine null 或空返回空串")
    void firstLine_nullOrEmpty_returnsEmpty(String s) {
        assertThat(Commit.firstLine(s)).isEmpty();
    }

    @Nested
    @DisplayName("多 parent 支持")
    class MultipleParents {

        /**
         * 场景：commit 对象体包含多行 parent &lt;oid&gt;，fromBytes 解析后 toBytes 再解析，parents 顺序与内容一致。
         */
        @Test
        @DisplayName("fromBytes 解析多个 parent 行，toBytes 再 fromBytes 后 parents 一致")
        void fromBytes_multipleParents_roundtripsCorrectly() {
            String body = "tree abc123\nparent p1\nparent p2\nparent p3\n"
                    + "author " + AUTHOR_FULL + "\ncommitter " + AUTHOR_FULL + "\n\nmsg";
            Commit c = Commit.fromBytes(body.getBytes(StandardCharsets.UTF_8));
            assertThat(c.getParentOids()).containsExactly("p1", "p2", "p3");
            byte[] serialized = c.toBytes();
            Commit c2 = Commit.fromBytes(serialized);
            assertThat(c2.getParentOids()).isEqualTo(c.getParentOids());
        }

        /**
         * 场景：commit 有多个 parent 时，兼容旧逻辑的 getParentOid() 应返回第一个 parent。
         */
        @Test
        @DisplayName("多 parent 时 getParentOid 返回第一个")
        void getParentOid_multipleParents_returnsFirst() {
            Commit c = new Commit("tree1", Arrays.asList("first", "second"), AUTHOR_FULL, AUTHOR_FULL, "msg");
            assertThat(c.getParentOid()).isEqualTo("first");
        }
    }
}
