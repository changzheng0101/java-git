package com.weixiao.obj;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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
}
