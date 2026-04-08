package com.weixiao.repo;

import com.weixiao.config.JitConfig;
import com.weixiao.obj.Commit;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

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

    /**
     * 与 Git {@code current_author} 类似：{@code GIT_AUTHOR_*} 优先，否则 config {@code user.name} / {@code user.email}（经 {@link JitConfig#get(String...)} 合并三层），再回退到系统用户名与 {@code name@local}。
     */
    private static String formatAuthor() {
        JitConfig cfg = Repository.INSTANCE.getJitConfig();
        String user = Optional.ofNullable(System.getProperty("user.name"))
                .orElseGet(
                        () -> cfg.get("user", "user").orElse("default_user").toString()
                );
        String email = Optional.ofNullable(System.getProperty("user.email"))
                .orElseGet(() -> cfg.get("user", "email").orElse("default_email").toString());

        OffsetDateTime now = OffsetDateTime.now();
        long sec = now.toEpochSecond();
        String tz = now.format(DateTimeFormatter.ofPattern("XX"));
        return user + " <" + email + "> " + sec + " " + tz;
    }


}
