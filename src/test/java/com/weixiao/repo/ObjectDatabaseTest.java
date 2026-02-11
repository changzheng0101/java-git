package com.weixiao.repo;

import com.weixiao.obj.Blob;
import com.weixiao.obj.Commit;
import com.weixiao.obj.Tree;
import com.weixiao.obj.TreeEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ObjectDatabase 测试")
class ObjectDatabaseTest {

    /**
     * 存储 Blob 后按返回的 oid 再 load，应得到类型 "blob" 且 body 与写入内容一致。
     * 示例：store(Blob("hello")) → oid 为 40 位小写 hex（如 "0b4e...")，load(oid) 得到 type=blob、body="hello"。
     */
    @Test
    @DisplayName("store Blob 后 load 得到相同内容")
    void storeAndLoadBlob(@TempDir Path dir) throws Exception {
        Path gitDir = dir.resolve(".git").resolve("objects");
        Files.createDirectories(gitDir.getParent().resolve("refs").resolve("heads"));
        ObjectDatabase db = new ObjectDatabase(dir.resolve(".git"));
        Blob blob = new Blob("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String oid = db.store(blob);
        assertThat(oid).hasSize(40);
        assertThat(oid).matches("[0-9a-f]{40}");
        ObjectDatabase.RawObject raw = db.load(oid);
        assertThat(raw.getType()).isEqualTo("blob");
        assertThat(new String(raw.getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    /**
     * 存储 Tree 后按 oid load，应得到类型 "tree"。
     * 示例：先 store 一个 Blob，再 store 一个只含一条 TreeEntry("100644", "a.txt", blobOid) 的 Tree，load(treeOid).getType() 为 "tree"。
     */
    @Test
    @DisplayName("store Tree 后 load 得到 tree 类型")
    void storeAndLoadTree(@TempDir Path dir) throws Exception {
        Path gitDir = dir.resolve(".git");
        Files.createDirectories(gitDir.resolve("objects"));
        Files.createDirectories(gitDir.resolve("refs").resolve("heads"));
        ObjectDatabase db = new ObjectDatabase(gitDir);
        Blob b = new Blob("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String blobOid = db.store(b);
        Tree tree = new Tree(List.of(new TreeEntry("100644", "a.txt", blobOid)));
        String treeOid = db.store(tree);
        assertThat(treeOid).hasSize(40);
        ObjectDatabase.RawObject raw = db.load(treeOid);
        assertThat(raw.getType()).isEqualTo("tree");
    }

    /**
     * 存储 Commit 后 load，应得到类型 "commit"，且 body 文本包含 tree oid 和提交信息。
     * 示例：store(Commit.first(treeOid, "u <u@local> 0 +0000", "first")) → load 得到 type=commit，body 含 "tree &lt;treeOid&gt;" 和 "first"。
     */
    @Test
    @DisplayName("store Commit 后 load 得到 commit 类型且 body 含 tree 与 message")
    void storeAndLoadCommit(@TempDir Path dir) throws Exception {
        Path gitDir = dir.resolve(".git");
        Files.createDirectories(gitDir.resolve("objects"));
        Files.createDirectories(gitDir.resolve("refs").resolve("heads"));
        ObjectDatabase db = new ObjectDatabase(gitDir);
        Blob b = new Blob("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String blobOid = db.store(b);
        Tree tree = new Tree(List.of(new TreeEntry("100644", "f", blobOid)));
        String treeOid = db.store(tree);
        Commit commit = Commit.first(treeOid, "u <u@local> 0 +0000", "first");
        String commitOid = db.store(commit);
        assertThat(commitOid).hasSize(40);
        ObjectDatabase.RawObject raw = db.load(commitOid);
        assertThat(raw.getType()).isEqualTo("commit");
        String body = new String(raw.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("tree " + treeOid);
        assertThat(body).contains("first");
    }
}
