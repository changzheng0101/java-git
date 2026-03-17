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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
     * 递归求两个提交的 Best Common Ancestor（Recursive Lowest Common Ancestor）。
     * 先从 X/Y 向上回溯，使用 parent1/parent2/result/stale 标记所有共同祖先，再过滤掉是其它候选祖先的提交。
     * todo 优化算法，当全部为stale的时候，就已经可以结束了 不用继续处理
     * todo 优化函数  函数目前太长了
     */
    static String findBca(String oid1, String oid2) throws IOException {
        if (oid1.equals(oid2)) {
            return oid1;
        }

        Repository repo = Repository.INSTANCE;
        ObjectDatabase db = repo.getDatabase();
        Map<String, Set<BcaFlag>> flags = new HashMap<>();
        List<String> candidates = new ArrayList<>();

        // 初始化：从两端 tip 开始回溯
        db.loadCommit(oid1);
        db.loadCommit(oid2);
        flags.put(oid1, EnumSet.of(BcaFlag.PARENT1));
        flags.put(oid2, EnumSet.of(BcaFlag.PARENT2));
        Deque<String> queue = new ArrayDeque<>(Arrays.asList(oid1, oid2));

        // 第一阶段：遍历直到队列为空，收集所有 RESULT 节点
        while (!queue.isEmpty()) {
            String oid = queue.poll();
            Commit c = db.loadCommit(oid);
            if (c == null) {
                continue;
            }
            Set<BcaFlag> curFlags = flags.computeIfAbsent(oid, k -> EnumSet.noneOf(BcaFlag.class));
            boolean hasP1 = curFlags.contains(BcaFlag.PARENT1);
            boolean hasP2 = curFlags.contains(BcaFlag.PARENT2);

            EnumSet<BcaFlag> propagate = EnumSet.copyOf(curFlags);
            if (hasP1 && hasP2 && !curFlags.contains(BcaFlag.RESULT)) {
                // 这是一个候选 BCA：标记 result，并在向父节点传播时附带 STALE，
                // 这样它的所有祖先最终都会被标记为 STALE。
                curFlags.add(BcaFlag.RESULT);
                candidates.add(oid);
                propagate.add(BcaFlag.STALE);
            }

            List<String> parents = c.getParentOids();
            if (parents == null || parents.isEmpty()) {
                continue;
            }
            for (String parentOid : parents) {
                db.loadCommit(parentOid);
                Set<BcaFlag> parentFlags = flags.computeIfAbsent(parentOid, k -> new HashSet<>());
                if (parentFlags.addAll(propagate)) {
                    queue.add(parentOid);
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // 第二阶段：剔除不是“best”的候选（是其它候选祖先的提交）
        List<String> results = new ArrayList<>(candidates);
        Set<String> toDrop = new HashSet<>();
        for (int i = 0; i < results.size(); i++) {
            for (int j = 0; j < results.size(); j++) {
                if (i == j) {
                    continue;
                }
                String a = results.get(i);
                String b = results.get(j);
                if (toDrop.contains(a) || toDrop.contains(b)) {
                    continue;
                }
                if (isAncestor(db, a, b)) {
                    // a 是 b 的祖先，则 a 不是 best
                    toDrop.add(a);
                }
                if (isAncestor(db, b, a)) {
                    // b 是 a 的祖先，则 b 不是 best
                    toDrop.add(b);
                }
            }
        }
        results.removeAll(toDrop);
        if (results.isEmpty()) {
            // 理论上不会发生，fallback：返回任意一个候选
            return candidates.get(0);
        }

        // 多个 best 时，目前调用方只需要一个，返回第一个即可（顺序稳定性由遍历顺序保证）
        return results.get(0);
    }

    /**
     * 判断 ancestor 是否为 descendant 的祖先（沿 parents 回溯）。
     */
    private static boolean isAncestor(ObjectDatabase db, String ancestor, String descendant) {
        if (ancestor.equals(descendant)) {
            return false;
        }
        Deque<String> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        stack.push(descendant);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (!visited.add(cur)) {
                continue;
            }
            Commit c = db.loadCommit(cur);
            if (c == null) {
                continue;
            }
            List<String> parents = c.getParentOids();
            if (parents == null || parents.isEmpty()) {
                continue;
            }
            for (String p : parents) {
                if (ancestor.equals(p)) {
                    return true;
                }
                stack.push(p);
            }
        }
        return false;
    }

    private static String formatAuthor() {
        String user = System.getProperty("user.name", "user");
        long sec = System.currentTimeMillis() / 1000;
        return user + " <" + user + "@local> " + sec + " +0000";
    }

    private enum BcaFlag {
        PARENT1,
        PARENT2,
        RESULT,
        STALE
    }
}
