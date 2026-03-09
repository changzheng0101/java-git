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
import java.util.Arrays;
import java.util.Collections;
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
        assertThat(RevList.mark("abc", RevList.CommitFlag.VISITED, flags)).isTrue();
        assertThat(RevList.mark("abc", RevList.CommitFlag.VISITED, flags)).isFalse();
        assertThat(RevList.mark("def", RevList.CommitFlag.VISITED, flags)).isTrue();
    }

    @Test
    @DisplayName("marked 未标记返回 false，已标记返回 true")
    void marked_returnsFalseUntilMarked() {
        Set<String> flags = new HashSet<>();
        assertThat(RevList.marked("oid1", RevList.CommitFlag.VISITED, flags)).isFalse();
        RevList.mark("oid1", RevList.CommitFlag.VISITED, flags);
        assertThat(RevList.marked("oid1", RevList.CommitFlag.VISITED, flags)).isTrue();
        assertThat(RevList.marked("oid2", RevList.CommitFlag.VISITED, flags)).isFalse();
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

    @Test
    @DisplayName("parseRevSpecs 解析 ^ 与 .. 语法")
    void parseRevSpecs_parsesCaretAndDotDot() {
        RevList.RevSpecResult r1 = RevList.parseRevSpecs(Arrays.asList("topic..master"));
        assertThat(r1.startRevisions()).containsExactly("master");
        assertThat(r1.excludeRevisions()).containsExactly("topic");

        RevList.RevSpecResult r2 = RevList.parseRevSpecs(Arrays.asList("^topic", "master"));
        assertThat(r2.startRevisions()).containsExactly("master");
        assertThat(r2.excludeRevisions()).containsExactly("topic");

        RevList.RevSpecResult r3 = RevList.parseRevSpecs(Arrays.asList("master"));
        assertThat(r3.startRevisions()).containsExactly("master");
        assertThat(r3.excludeRevisions()).isEmpty();

        RevList.RevSpecResult r4 = RevList.parseRevSpecs(Collections.emptyList());
        assertThat(r4.startRevisions()).containsExactly("HEAD");
        assertThat(r4.excludeRevisions()).isEmpty();
    }

    @Test
    @DisplayName("walkWithExcludes topic..master 只输出在 master 不在 topic 的提交")
    void walkWithExcludes_dotDot_onlyCommitsInStartNotInExclude(@TempDir Path tempDir) throws Exception {
        initRepoWithBranch(tempDir);
        Repository.find(tempDir);
        String masterOid = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "master"));
        String devOid = Repository.INSTANCE.getRefs().readRef(new SysRef(Refs.REFS_HEADS + "dev"));
        assertThat(devOid).isNotEqualTo(masterOid)
                .as("dev 应在 third 提交后指向新 commit，与 master 不同");

        // HEAD 当前在 dev，应解析为 third；排除 master（second）后仅剩 third
        List<RevList.CommitEntry> inDevNotMaster = RevList.walk(
                new RevList.RevSpecResult(List.of("HEAD"), List.of(masterOid)));
        assertThat(inDevNotMaster).hasSize(1);
        assertThat(inDevNotMaster.get(0).oid()).isEqualTo(devOid);
        assertThat(inDevNotMaster.get(0).commit().getMessage()).contains("third");

        // dev 为 third→second→first，master 为 second→first，故 master..dev 为空（master 无 dev 不可达的提交）
        List<RevList.CommitEntry> inMasterNotDev = RevList.walk(
                new RevList.RevSpecResult(List.of(masterOid), List.of(devOid)));
        assertThat(inMasterNotDev).isEmpty();
    }

    @Test
    @DisplayName("walk(RevSpecResult) 无排除点时与 walk(String) 行为一致")
    void walkRevSpecResult_noExcludes_sameAsWalk(@TempDir Path tempDir) throws Exception {
        initRepoWithTwoCommits(tempDir);
        Repository.find(tempDir);

        List<RevList.CommitEntry> withSpec = RevList.walk(new RevList.RevSpecResult(List.of("HEAD"), List.of()));
        List<RevList.CommitEntry> plain = RevList.walk("HEAD");
        assertThat(withSpec).hasSize(plain.size());
        assertThat(withSpec.stream().map(RevList.CommitEntry::oid).collect(Collectors.toList()))
                .containsExactlyElementsOf(plain.stream().map(RevList.CommitEntry::oid).collect(Collectors.toList()));
    }
}
