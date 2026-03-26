package com.weixiao.repo;

import com.weixiao.obj.TreeEntry;
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

    private static String oid(char c) {
        char[] chars = new char[40];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    @DisplayName("add 目录下的文件时移除同名文件条目")
    void add_fileUnderDir_removesSameNameFile(@TempDir Path gitDir) {
        Index index = new Index(gitDir);
        index.add(new Index.Entry("alice.txt", "100644", oid('a'), 0, 5, ZERO_STAT));
        index.add(new Index.Entry("alice.txt/nested.txt", "100644", oid('b'), 0, 6, ZERO_STAT));

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
        index.add(new Index.Entry("nested/bob.txt", "100644", oid('a'), 0, 3, ZERO_STAT));
        index.add(new Index.Entry("nested/inner/claire.txt", "100644", oid('b'), 0, 6, ZERO_STAT));
        index.add(new Index.Entry("nested", "100644", oid('c'), 0, 4, ZERO_STAT));

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
        index.add(new Index.Entry("hello.txt", "100644", oid('a'), 0, 5, ZERO_STAT));
        index.add(new Index.Entry("hello.txt/a.txt", "100644", oid('b'), 0, 1, ZERO_STAT));

        List<String> paths = index.getEntries().stream()
                .map(Index.Entry::getPath)
                .sorted()
                .collect(Collectors.toList());
        assertThat(paths).containsExactly("hello.txt/a.txt");
        assertThat(paths).doesNotContain("hello.txt");
    }

    @Test
    @DisplayName("addConflictSet 先清理 stage-0 并按 1/2/3 写入非空冲突条目")
    void addConflictSet_replacesStage0AndWritesConflictStages(@TempDir Path gitDir) {
        Index index = new Index(gitDir);
        index.add(new Index.Entry("a.txt", "100644", oid('z'), 0, 10, ZERO_STAT));

        TreeEntry base = new TreeEntry("100644", "a.txt", oid('a'));
        TreeEntry ours = new TreeEntry("100644", "a.txt", oid('b'));

        index.addConflictSet("a.txt", base, ours, null);

        assertThat(index.getEntryForPath("a.txt", 0)).isNull();
        assertThat(index.getEntryForPath("a.txt", 1)).isNotNull();
        assertThat(index.getEntryForPath("a.txt", 2)).isNotNull();
        assertThat(index.getEntryForPath("a.txt", 3)).isNull();
    }

    @Test
    @DisplayName("isConflicted 只要存在非 0 stage 条目就返回 true")
    void isConflicted_trueWhenAnyNonZeroStage(@TempDir Path gitDir) {
        Index index = new Index(gitDir);
        assertThat(index.isConflicted()).isFalse();

        index.add(new Index.Entry("a.txt", "100644", oid('a'), 0, 1, ZERO_STAT));
        assertThat(index.isConflicted()).isFalse();

        TreeEntry ours = new TreeEntry("100644", "a.txt", oid('b'));
        index.addConflictSet("a.txt", null, ours, null);
        assertThat(index.isConflicted()).isTrue();
    }

    @Test
    @DisplayName("add stage-0 条目时会清理同路径的 stage 1/2/3")
    void add_stage0_clearsConflictStages(@TempDir Path gitDir) {
        Index index = new Index(gitDir);
        TreeEntry base = new TreeEntry("100644", "a.txt", oid('a'));
        TreeEntry ours = new TreeEntry("100644", "a.txt", oid('b'));
        TreeEntry theirs = new TreeEntry("100644", "a.txt", oid('c'));
        index.addConflictSet("a.txt", base, ours, theirs);

        index.add(new Index.Entry("a.txt", "100644", oid('d'), 0, 1, ZERO_STAT));

        assertThat(index.getEntryForPath("a.txt", 0)).isNotNull();
        assertThat(index.getEntryForPath("a.txt", 1)).isNull();
        assertThat(index.getEntryForPath("a.txt", 2)).isNull();
        assertThat(index.getEntryForPath("a.txt", 3)).isNull();
    }
}
