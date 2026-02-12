package com.weixiao.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Index 冲突移除逻辑测试：文件/目录不能同名。
 */
@DisplayName("Index 测试")
class IndexTest {

    private static final Index.IndexStat ZERO_STAT =
            new Index.IndexStat(0, 0, 0, 0, 0, 0, 0, 0);

    @Test
    @DisplayName("add 目录下的文件时移除同名文件条目")
    void add_fileUnderDir_removesSameNameFile(@TempDir Path gitDir) {
        Index index = new Index(gitDir);
        index.add("alice.txt", "100644", "a".repeat(40), 5, ZERO_STAT);
        index.add("alice.txt/nested.txt", "100644", "b".repeat(40), 6, ZERO_STAT);

        List<String> paths = index.getEntries().stream()
                .map(Index.Entry::getPath)
                .sorted()
                .collect(Collectors.toList());
        assertThat(paths).containsExactly("alice.txt/nested.txt");
        assertThat(paths).doesNotContain("alice.txt");
    }

    @Test
    @DisplayName("add 普通文件时移除同名目录下的所有条目")
    void add_file_removesSameNameDirEntries(@TempDir Path gitDir) {
        Index index = new Index(gitDir);
        index.add("nested/bob.txt", "100644", "a".repeat(40), 3, ZERO_STAT);
        index.add("nested/inner/claire.txt", "100644", "b".repeat(40), 6, ZERO_STAT);
        index.add("nested", "100644", "c".repeat(40), 4, ZERO_STAT);

        List<String> paths = index.getEntries().stream()
                .map(Index.Entry::getPath)
                .sorted()
                .collect(Collectors.toList());
        assertThat(paths).containsExactly("nested");
        assertThat(paths).doesNotContain("nested/bob.txt");
        assertThat(paths).doesNotContain("nested/inner/claire.txt");
    }

    @Test
    @DisplayName("add hello.txt 再 add hello.txt/a.txt 时只保留 hello.txt/a.txt")
    void add_helloThenHelloSlashA_removesHello(@TempDir Path gitDir) {
        Index index = new Index(gitDir);
        index.add("hello.txt", "100644", "a".repeat(40), 5, ZERO_STAT);
        index.add("hello.txt/a.txt", "100644", "b".repeat(40), 1, ZERO_STAT);

        List<String> paths = index.getEntries().stream()
                .map(Index.Entry::getPath)
                .sorted()
                .collect(Collectors.toList());
        assertThat(paths).containsExactly("hello.txt/a.txt");
        assertThat(paths).doesNotContain("hello.txt");
    }
}
