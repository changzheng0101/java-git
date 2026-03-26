package com.weixiao.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * merge 进行中的临时提交信息：MERGE_HEAD 与 MERGE_MSG。
 */
public final class PendingCommit {

    private static final String MERGE_HEAD = "MERGE_HEAD";
    private static final String MERGE_MSG = "MERGE_MSG";

    private final Path gitDir;

    public PendingCommit(Path gitDir) {
        this.gitDir = gitDir;
    }

    /**
     * 记录一次待完成 merge 的右侧提交与默认提交消息。
     */
    public void start(String mergeHeadOid, String mergeMessage) throws IOException {
        Files.writeString(gitDir.resolve(MERGE_HEAD), mergeHeadOid + "\n", StandardCharsets.UTF_8);
        Files.writeString(gitDir.resolve(MERGE_MSG), mergeMessage + "\n", StandardCharsets.UTF_8);
    }

    /**
     * 清理 merge 过程文件。
     */
    public void clear() throws IOException {
        Files.deleteIfExists(gitDir.resolve(MERGE_HEAD));
        Files.deleteIfExists(gitDir.resolve(MERGE_MSG));
    }

    /**
     * 是否存在未完成的 merge（通过 MERGE_HEAD 是否存在判断）。
     */
    public boolean inProgress() {
        return Files.exists(gitDir.resolve(MERGE_HEAD));
    }

    /**
     * 读取 MERGE_HEAD（右侧提交 oid）。
     */
    public String readMergeHead() throws IOException {
        return Files.readString(gitDir.resolve(MERGE_HEAD), StandardCharsets.UTF_8).trim();
    }

    /**
     * 读取 MERGE_MSG（待使用的 merge 提交消息）。
     */
    public String readMergeMessage() throws IOException {
        return Files.readString(gitDir.resolve(MERGE_MSG), StandardCharsets.UTF_8).trim();
    }
}
