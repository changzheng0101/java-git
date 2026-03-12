package com.weixiao.command;

import com.weixiao.obj.Commit;
import com.weixiao.repo.Migration;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;
import com.weixiao.repo.TreeBuilder;
import com.weixiao.revision.Revision;
import com.weixiao.revision.RevisionParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * jit merge：将指定分支与当前 HEAD 合并。
 * 当前仅实现一个简化版本：计算 base（BCA）到 merge 分支 tip 的净变化，并把这部分变化应用到当前工作区与 index，
 * 然后从更新后的 index 写一个新的 merge commit（parents=HEAD, merge_tip）。
 */
@Command(name = "merge", mixinStandardHelpOptions = true, description = "合并指定分支到当前 HEAD（简化版：应用 merge 分支净变化并写 merge commit）")
public class MergeCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(MergeCommand.class);

    @Parameters(index = "0", paramLabel = "REV", description = "要合并的分支或修订")
    private String rev;

    @Override
    protected void doRun() {
        try {
            String headOid = repo.getRefs().readHead();
            if (headOid == null || headOid.isEmpty()) {
                System.err.println("fatal: Not a valid object name: 'HEAD'.");
                exitCode = 1;
                return;
            }
            String mergeTipOid = Revision.parse(rev).getCommitId(repo);
            String baseOid = findBca(headOid, mergeTipOid);
            if (baseOid == null) {
                System.err.println("fatal: no common ancestor found.");
                exitCode = 1;
                return;
            }

            Migration migration = new Migration(baseOid, mergeTipOid);
            migration.validate();
            migration.applyChanges();

            repo.getIndex().load();
            String treeOid = TreeBuilder.buildTreeFromIndex(repo.getIndex().getEntries());
            String author = formatAuthor();
            String mergeMsg = "Merge branch '" + rev + "'";
            Commit mergeCommit = new Commit(treeOid, Arrays.asList(headOid, mergeTipOid), author, author, mergeMsg);
            String newCommitOid = repo.getDatabase().store(mergeCommit);
            repo.getRefs().updateCurrentBranch(newCommitOid);
            System.out.println("Merge made. New commit: " + ObjectDatabase.shortOid(newCommitOid));
        } catch (RevisionParseException e) {
            log.warn("merge parse revision failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        } catch (IOException e) {
            log.error("merge failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }



    /**
     * 用 parent1/parent2 标记沿父指针回溯，首次同时拥有两标记的 commit 即为 BCA。
     * 仅考虑线性历史（每个 commit 至多一个 parent）。
     */
    static String findBca(String oid1, String oid2) throws IOException {
        if (oid1.equals(oid2)) {
            return oid1;
        }

        Repository repo = Repository.INSTANCE;
        ObjectDatabase db = repo.getDatabase();
        Map<String, Set<BcaFlag>> flags = new HashMap<>();
        db.loadCommit(oid1);
        db.loadCommit(oid2);
        flags.put(oid1, EnumSet.of(BcaFlag.PARENT1));
        flags.put(oid2, EnumSet.of(BcaFlag.PARENT2));
        Deque<String> queue = new ArrayDeque<>(Arrays.asList(oid1, oid2));

        while (!queue.isEmpty()) {
            String oid = queue.poll();
            Commit c = db.loadCommit(oid);
            if (c == null) {
                continue;
            }
            String parentOid = c.getParentOid();
            if (parentOid == null) {
                continue;
            }
            db.loadCommit(parentOid);
            Set<BcaFlag> parentFlags = flags.computeIfAbsent(parentOid, k -> new HashSet<>());
            parentFlags.addAll(flags.get(oid));
            if (parentFlags.size() == 2) {
                return parentOid;
            }
            queue.add(parentOid);
        }
        return null;
    }

    private static String formatAuthor() {
        String user = System.getProperty("user.name", "user");
        long sec = System.currentTimeMillis() / 1000;
        return user + " <" + user + "@local> " + sec + " +0000";
    }

    private enum BcaFlag {
        PARENT1,
        PARENT2
    }
}
