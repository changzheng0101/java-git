package com.weixiao.repo;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.obj.Commit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RevList 测试")
class RevListTest {

    private static final CommandLine JIT = Jit.createCommandLine();

    private static void initRepoWithTwoCommits(Path tempDir) throws Exception {
        JIT.execute("-C", tempDir.toString(), "init");
        Path f = tempDir.resolve("f.txt");
        Files.write(f, "v1".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "first");

        Files.write(f, "v2".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "second");
    }

    private static void initRepoWithBranch(Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "branch", "dev");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "checkout", "dev");
        Path f = tempDir.resolve("f.txt");
        Files.write(f, "v3".getBytes(StandardCharsets.UTF_8));
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "add", "f.txt");
        JitTestUtil.executeWithCapturedOut(JIT, "-C", tempDir.toString(), "commit", "-m", "third on dev");
    }

    @Test
    @DisplayName("mark 首次添加返回 true，重复添加返回 false")
    void mark_returnsTrueFirstTimeFalseWhenAlreadySet() {
        Set<String> flags = new HashSet<>();
        assertThat(RevList.mark("abc", RevList.FLAG_VISITED, flags)).isTrue();
        assertThat(RevList.mark("abc", RevList.FLAG_VISITED, flags)).isFalse();
        assertThat(RevList.mark("def", RevList.FLAG_VISITED, flags)).isTrue();
    }

    @Test
    @DisplayName("marked 未标记返回 false，已标记返回 true")
    void marked_returnsFalseUntilMarked() {
        Set<String> flags = new HashSet<>();
        assertThat(RevList.marked("oid1", RevList.FLAG_VISITED, flags)).isFalse();
        RevList.mark("oid1", RevList.FLAG_VISITED, flags);
        assertThat(RevList.marked("oid1", RevList.FLAG_VISITED, flags)).isTrue();
        assertThat(RevList.marked("oid2", RevList.FLAG_VISITED, flags)).isFalse();
    }

    @Test
    @DisplayName("walk 无参等价于 HEAD，按时间从新到旧")
    void walk_noArgs_usesHead_newestFirst(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        Repository.find(tempDir);

        List<RevList.CommitEntry> entries = RevList.walk();

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).commit().getMessage()).contains("second");
        assertThat(entries.get(1).commit().getMessage()).contains("first");
    }

    @Test
    @DisplayName("walk 单 revision 返回该起点及祖先")
    void walk_singleRevision_returnsReachable(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        Repository.find(tempDir);
        String headOid = Repository.INSTANCE.getRefs().readHead();

        List<RevList.CommitEntry> entries = RevList.walk(headOid);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).oid()).isEqualTo(headOid);
    }

    @Test
    @DisplayName("walk 多 revision 合并历史且每个 commit 只出现一次")
    void walk_multipleRevisions_mergedHistory_noDuplicate(@TempDir Path tempDir) throws Exception {
        initRepoWithBranch(tempDir);
        Repository.find(tempDir);
        String masterOid = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "master"));
        String devOid = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "dev"));

        List<RevList.CommitEntry> entries = RevList.walk("master", "dev");

        assertThat(entries).hasSize(3);
        List<String> oids = entries.stream().map(RevList.CommitEntry::oid).collect(Collectors.toList());
        assertThat(oids).doesNotHaveDuplicates();
        assertThat(oids).contains(masterOid, devOid);
        List<String> messages = entries.stream().map(e -> e.commit().getMessage()).collect(Collectors.toList());
        assertThat(messages).anyMatch(m -> m.contains("third"));
        assertThat(messages).anyMatch(m -> m.contains("second"));
        assertThat(messages).anyMatch(m -> m.contains("first"));
    }

    @Test
    @DisplayName("walk 同一分支写两次仍只出现一次")
    void walk_sameBranchTwice_noDuplicate(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        Repository.find(tempDir);

        List<RevList.CommitEntry> entries = RevList.walk("master", "master");

        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(RevList.CommitEntry::oid).collect(Collectors.toList())).doesNotHaveDuplicates();
    }
}
