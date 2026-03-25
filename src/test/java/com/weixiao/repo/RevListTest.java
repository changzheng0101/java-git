package com.weixiao.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RevList 测试")
class RevListTest {

    @Test
    @DisplayName("parseRevSpecs 解析 ^ 与 .. 语法")
    void parseRevSpecs_parsesCaretAndDotDot() {
        RevList.RevSpecResult r1 = RevList.parseRevSpecs(List.of("topic..master"));
        assertThat(r1.startRevisions()).containsExactly("master");
        assertThat(r1.excludeRevisions()).containsExactly("topic");

        RevList.RevSpecResult r2 = RevList.parseRevSpecs(Arrays.asList("^topic", "master"));
        assertThat(r2.startRevisions()).containsExactly("master");
        assertThat(r2.excludeRevisions()).containsExactly("topic");

        RevList.RevSpecResult r3 = RevList.parseRevSpecs(List.of("master"));
        assertThat(r3.startRevisions()).containsExactly("master");
        assertThat(r3.excludeRevisions()).isEmpty();

        RevList.RevSpecResult r4 = RevList.parseRevSpecs(Collections.emptyList());
        assertThat(r4.startRevisions()).containsExactly("HEAD");
        assertThat(r4.excludeRevisions()).isEmpty();
    }

}
