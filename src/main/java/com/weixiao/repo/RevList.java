package com.weixiao.repo;

import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.revision.Revision;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 类似 git rev-list：从给定 revision 开始沿 parent 链遍历，找出符合条件的提交。
 * 支持多个 revision、排除点（^rev 或 A..B），合并历史、按提交时间“最晚优先”遍历。
 * 不负责格式化或输出，仅返回 (oid, Commit) 列表，供 log 等上层命令使用。
 */
public final class RevList {

    private RevList() {
    }

    /**
     * 遍历时对 commit 打的标记。
     */
    public enum CommitFlag {
        /**
         * 已处理（已出队并处理过）。
         */
        VISITED,
        /**
         * 排除或由排除可达的 commit，不加入输出。
         */
        UNINTERESTING
    }

    /**
     * 解析修订说明列表为“起点”与“排除点”。
     * - {@code A..B} 等价于 exclude A、start B（B 可达且 A 不可达的提交）
     * - {@code ^A} 表示排除 A
     * - 其它为起点
     * 例如 {@code ["^topic", "master"]} 与 {@code ["topic..master"]} 等价。
     */
    public static RevSpecResult parseRevSpecs(List<String> revisions) {
        if (revisions == null || Iterables.isEmpty(revisions)) {
            return new RevSpecResult(Collections.singletonList("HEAD"), Lists.newArrayList());
        }

        List<String> starts = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        for (String revision : revisions) {
            if (revision.contains("..")) {
                int i = revision.indexOf("..");
                String left = revision.substring(0, i);
                String right = revision.substring(i + 2);
                excludes.add(left.isEmpty() ? "HEAD" : left);
                starts.add(right.isEmpty() ? "HEAD" : right);
            } else if (revision.startsWith("^")) {
                if (revision.length() > 1) {
                    excludes.add(revision.substring(1).trim());
                }
            } else {
                starts.add(revision);
            }
        }
        if (starts.isEmpty()) {
            starts.add("HEAD");
        }

        return new RevSpecResult(starts, excludes);
    }

    /**
     * 解析结果：起点列表 + 排除点列表。
     */
    public record RevSpecResult(List<String> startRevisions, List<String> excludeRevisions) {
    }

    /**
     * 从多个 revision 出发，合并遍历所有可达提交，按提交时间从新到旧，每个 commit 只出现一次。
     * 无参数时等价于 walk(parseRevSpecs(HEAD))。仓库通过 {@link Repository#INSTANCE} 单例获取。
     *
     * @param revisions 修订说明（HEAD、分支名、oid 等），可为空表示默认 HEAD
     * @return 按提交时间从新到旧的 (oid, Commit) 列表
     */
    public static List<CommitEntry> walk(String... revisions) throws IOException {
        List<String> revList = (revisions != null && revisions.length > 0)
                ? List.of(revisions)
                : List.of("HEAD");
        return walk(parseRevSpecs(revList));
    }

    /**
     * 根据解析结果遍历：从起点出发，排除“由排除点可达”的提交。
     * 等价于 git log start.. 或 git log ^exclude start：只输出从 start 可达且从 exclude 不可达的提交。
     * excludeRevisions 为空时等价于仅用 startRevisions 做普通 walk。
     *
     * @param spec 由 {@link #parseRevSpecs(List)} 得到的起点与排除点
     * @return 按提交时间从新到旧的 (oid, Commit) 列表
     */
    public static List<CommitEntry> walk(RevSpecResult spec) throws IOException {
        Repository repo = Repository.INSTANCE;
        Collection<String> startRevisions = spec.startRevisions();
        Collection<String> excludeRevisions = spec.excludeRevisions() != null ? spec.excludeRevisions() : List.of();
        Map<String, Commit> commits = new HashMap<>();
        Set<String> flags = new HashSet<>();
        Comparator<String> byTimeDesc = (a, b) -> {
            long ta = Commit.getAuthorTimestamp(commits.get(a).getAuthor());
            long tb = Commit.getAuthorTimestamp(commits.get(b).getAuthor());
            int c = Long.compare(tb, ta);
            return c != 0 ? c : a.compareTo(b);
        };
        PriorityQueue<String> queue = new PriorityQueue<>(byTimeDesc);
        Set<String> queueOids = new LinkedHashSet<>();

        List<String> startList = Iterables.isEmpty(startRevisions)
                ? List.of("HEAD")
                : new ArrayList<>(startRevisions);
        for (String rev : startList) {
            String oid = Revision.parse(rev.trim()).getCommitId(repo);
            if (loadCommit(repo, oid, commits)) {
                queue.add(oid);
                queueOids.add(oid);
            }
        }

        if (!Iterables.isEmpty(excludeRevisions)) {
            Deque<String> toMark = new ArrayDeque<>();
            for (String rev : excludeRevisions) {
                String oid = Revision.parse(rev.trim()).getCommitId(repo);
                if (loadCommit(repo, oid, commits)) {
                    toMark.add(oid);
                }
            }
            while (!toMark.isEmpty()) {
                String oid = toMark.poll();
                if (marked(oid, CommitFlag.UNINTERESTING, flags)) {
                    continue;
                }
                mark(oid, CommitFlag.UNINTERESTING, flags);
                Commit c = commits.get(oid);
                String parentOid = c.getParentOid();
                if (parentOid != null && loadCommit(repo, parentOid, commits)) {
                    toMark.add(parentOid);
                }
            }
        }

        List<CommitEntry> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            if (!queueOids.isEmpty()
                    && queueOids.stream().allMatch(oid -> marked(oid, CommitFlag.UNINTERESTING, flags))) {
                break;
            }
            String oid = queue.poll();
            queueOids.remove(oid);
            if (marked(oid, CommitFlag.VISITED, flags)) {
                continue;
            }
            mark(oid, CommitFlag.VISITED, flags);
            Commit commit = commits.get(oid);

            if (marked(oid, CommitFlag.UNINTERESTING, flags)) {
                String parentOid = commit.getParentOid();
                if (parentOid != null && loadCommit(repo, parentOid, commits)) {
                    mark(parentOid, CommitFlag.UNINTERESTING, flags);
                    queue.add(parentOid);
                    queueOids.add(parentOid);
                }
                continue;
            }

            result.add(new CommitEntry(oid, commit));
            String parentOid = commit.getParentOid();
            if (parentOid != null && loadCommit(repo, parentOid, commits)) {
                queue.add(parentOid);
                queueOids.add(parentOid);
            }
        }
        return result;
    }

    /**
     * 加载 commit 到缓存；若已存在则不重复加载。
     *
     * @return 是否为 commit 且已放入缓存（非 commit 不放入，返回 false）
     */
    private static boolean loadCommit(Repository repo, String oid, Map<String, Commit> commits)
            throws IOException {
        if (commits.containsKey(oid)) {
            return true;
        }
        GitObject obj = repo.getDatabase().load(oid);
        if (!"commit".equals(obj.getType())) {
            return false;
        }
        commits.put(oid, Commit.fromBytes(obj.toBytes()));
        return true;
    }

    /**
     * 为 commit 打上标记；若该 commit 已有此标记则返回 false。
     */
    public static boolean mark(String oid, CommitFlag flag, Set<String> flags) {
        String key = oid + ":" + flag.name();
        return flags.add(key);
    }

    /**
     * 检查 commit 是否已有某标记。
     */
    public static boolean marked(String oid, CommitFlag flag, Set<String> flags) {
        return flags.contains(oid + ":" + flag.name());
    }

    /**
     * rev-list 遍历得到的一条记录：commit oid + 解析后的 Commit。
     */
    public record CommitEntry(String oid, Commit commit) {
    }
}
