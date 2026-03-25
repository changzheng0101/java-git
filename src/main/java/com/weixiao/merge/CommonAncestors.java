package com.weixiao.merge;

import com.weixiao.obj.Commit;
import com.weixiao.repo.ObjectDatabase;
import com.weixiao.repo.Repository;

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
 * 共同祖先相关工具：用于求两个提交的 Best Common Ancestor（RLCA 版本）。
 */
public final class CommonAncestors {

    private CommonAncestors() {
    }

    /**
     * 递归求两个提交的 Best Common Ancestor（Recursive Lowest Common Ancestor）。
     * 先从 X/Y 向上回溯，使用 parent1/parent2/result/stale 标记共同祖先候选，再过滤掉是其它候选祖先的提交。
     */
    public static String findBestCommonAncestor(String oid1, String oid2) {
        if (oid1.equals(oid2)) {
            return oid1;
        }

        ObjectDatabase db = Repository.INSTANCE.getDatabase();
        Map<String, Set<BcaFlag>> flags = new HashMap<>();
        List<String> candidates = new ArrayList<>();

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
            Set<BcaFlag> curFlags = flags.computeIfAbsent(oid, k -> EnumSet.noneOf(BcaFlag.class));
            boolean hasP1 = curFlags.contains(BcaFlag.PARENT1);
            boolean hasP2 = curFlags.contains(BcaFlag.PARENT2);

            EnumSet<BcaFlag> propagate = EnumSet.copyOf(curFlags);
            if (hasP1 && hasP2 && !curFlags.contains(BcaFlag.RESULT)) {
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
                    toDrop.add(a);
                }
                if (isAncestor(db, b, a)) {
                    toDrop.add(b);
                }
            }
        }
        results.removeAll(toDrop);
        if (results.isEmpty()) {
            return candidates.get(0);
        }
        return results.get(0);
    }

    /**
     * 判断 ancestor 是否为 descendant 的祖先（沿 parents 回溯）。
     */
    public static boolean isAncestor(String ancestor, String descendant) {
        return isAncestor(Repository.INSTANCE.getDatabase(), ancestor, descendant);
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

    private enum BcaFlag {
        PARENT1,
        PARENT2,
        RESULT,
        STALE
    }
}

