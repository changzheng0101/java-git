package com.weixiao.repo;

import com.weixiao.obj.Commit;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.util.List;

/**
 * 写入 commit 的工具类：基于当前 index 构建 tree 并提交。
 */
@UtilityClass
public final class WriteCommit {

    /**
     * 基于当前 index 写入一个 commit，并更新当前分支/HEAD。
     */
    public static String writeCommit(List<String> parents, String commitMsg) throws IOException {
        Repository repo = Repository.INSTANCE;
        String treeOid = TreeBuilder.buildTreeFromIndex(repo.getIndex().getEntries());
        String author = formatAuthor();
        Commit commit = new Commit(treeOid, parents, author, author, commitMsg);
        String newCommitOid = repo.getDatabase().store(commit);
        repo.getRefs().updateCurrentBranch(newCommitOid);
        return newCommitOid;
    }

    private static String formatAuthor() {
        String user = System.getProperty("user.name", "user");
        long sec = System.currentTimeMillis() / 1000;
        return user + " <" + user + "@local> " + sec + " +0000";
    }
}
