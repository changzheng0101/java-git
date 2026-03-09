package com.weixiao.repo;

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
import java.util.function.Consumer;

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
         * 代表已经处理过
         */
        SEEN,
        /**
         * 一定不会输出 从北排除的Revision可以访问到
         */
        UNINTERESTING,
        /**
         * 需要输出
         */
        ADDED
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
     * 根据解析结果遍历，每产生一条提交即回调 consumer，不积攒列表。
     * 适合 log 等流式输出场景。
     *
     * @param spec     由 {@link #parseRevSpecs(List)} 得到的起点与排除点
     * @param consumer 每遍历到一条提交时调用
     */
    public static void walk(RevSpecResult spec, Consumer<CommitEntry> consumer) throws IOException {
        Repository repo = Repository.INSTANCE;
        Collection<String> startRevisions = spec.startRevisions();
        Collection<String> excludeRevisions = spec.excludeRevisions();

        // commitId -> Commit Obj
        Map<String, Commit> commits = new HashMap<>();
        // commitId -> flags
        Map<String, Set<CommitFlag>> flags = new HashMap<>();
        Comparator<String> byTimeDesc = (a, b) -> {
            long ta = Commit.getAuthorTimestamp(commits.get(a).getAuthor());
            long tb = Commit.getAuthorTimestamp(commits.get(b).getAuthor());
            int c = Long.compare(tb, ta);
            return c != 0 ? c : a.compareTo(b);
        };
        // queue to show commit 时间越晚的排在越前面，越先输出
        PriorityQueue<String> logQueue = new PriorityQueue<>(byTimeDesc);
        // 用于给commit打标 时间越晚的排在越前面，越先输出
        PriorityQueue<String> processQueue = new PriorityQueue<>(byTimeDesc);

        // init
        for (String rev : startRevisions) {
            String oid = Revision.parse(rev.trim()).getCommitId(repo);
            loadCommit(oid, commits);
            processQueue.add(oid);
            mark(oid, CommitFlag.SEEN, flags);
        }

        if (!Iterables.isEmpty(excludeRevisions)) {
            for (String rev : excludeRevisions) {
                String oid = Revision.parse(rev.trim()).getCommitId(repo);
                loadCommit(oid, commits);
                processQueue.add(oid);
                mark(oid, CommitFlag.SEEN, flags);
                mark(oid, CommitFlag.UNINTERESTING, flags);
            }
        }

        // 遍历更改状态
        while (!processQueue.isEmpty()) {
            String oid = processQueue.poll();
            Commit commit = commits.get(oid);

            String parentCommitId = commit.getParentOid();
            if (parentCommitId != null) {
                loadCommit(parentCommitId, commits);
                mark(parentCommitId, CommitFlag.SEEN, flags);
                processQueue.add(parentCommitId);
            }
            if (marked(oid, CommitFlag.UNINTERESTING, flags)) {
                mark(parentCommitId, CommitFlag.UNINTERESTING, flags);
            }
            if (!marked(oid, CommitFlag.UNINTERESTING, flags)) {
                logQueue.add(oid);
            }


            if (!logQueue.isEmpty()) {
                String logCommitId = logQueue.poll();
                consumer.accept(new CommitEntry(logCommitId, commits.get(logCommitId)));
            }
        }

        // 处理剩余数据
        while (!logQueue.isEmpty()) {
            String logCommitId = logQueue.poll();
            consumer.accept(new CommitEntry(logCommitId, commits.get(logCommitId)));
        }
    }

    /**
     * 加载 commit 到缓存；若已存在则不重复加载。
     *
     * @return 是否为 commit 且已放入缓存（非 commit 不放入，返回 false）
     */
    private static void loadCommit(String oid, Map<String, Commit> commits)
            throws IOException {
        if (commits.containsKey(oid)) {
            return;
        }
        GitObject obj = Repository.INSTANCE.getDatabase().load(oid);

        if (!"commit".equals(obj.getType())) {
            throw new RuntimeException("commitId不合法");
        }
        commits.put(oid, Commit.fromBytes(obj.toBytes()));
    }

    /**
     * 为 commit 打上标记；若该 commit 已有此标记则返回 false。
     */
    public static boolean mark(String oid, CommitFlag flag, Map<String, Set<CommitFlag>> flags) {
        return flags.computeIfAbsent(oid, k -> new HashSet<>()).add(flag);
    }

    /**
     * 检查 commit 是否已有某标记。
     */
    public static boolean marked(String oid, CommitFlag flag, Map<String, Set<CommitFlag>> flags) {
        Set<CommitFlag> set = flags.get(oid);
        return set != null && set.contains(flag);
    }

    /**
     * rev-list 遍历得到的一条记录：commit oid + 解析后的 Commit。
     */
    public record CommitEntry(String oid, Commit commit) {
    }
}
