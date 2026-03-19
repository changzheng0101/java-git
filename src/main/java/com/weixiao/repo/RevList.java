package com.weixiao.repo;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.weixiao.obj.Commit;
import com.weixiao.revision.Revision;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    public static final class RevSpecResult {

        private final List<String> startRevisions;
        private final List<String> excludeRevisions;

        public RevSpecResult(List<String> startRevisions, List<String> excludeRevisions) {
            this.startRevisions = startRevisions;
            this.excludeRevisions = excludeRevisions;
        }

        public List<String> startRevisions() {
            return startRevisions;
        }

        public List<String> excludeRevisions() {
            return excludeRevisions;
        }
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
        ObjectDatabase db = repo.getDatabase();
        Collection<String> startRevisions = spec.startRevisions();
        Collection<String> excludeRevisions = spec.excludeRevisions();

        Map<String, Set<CommitFlag>> flags = new HashMap<>();
        Comparator<String> byTimeDesc = (a, b) -> {
            Commit ca = db.loadCommit(a);
            Commit cb = db.loadCommit(b);
            if (ca == null && cb == null) {
                return a.compareTo(b);
            }
            if (ca == null) {
                return 1;
            }
            if (cb == null) {
                return -1;
            }
            long ta = Commit.getAuthorTimestamp(ca.getAuthor());
            long tb = Commit.getAuthorTimestamp(cb.getAuthor());
            int c = Long.compare(tb, ta);
            return c != 0 ? c : a.compareTo(b);
        };
        PriorityQueue<String> logQueue = new PriorityQueue<>(byTimeDesc);
        PriorityQueue<String> processQueue = new PriorityQueue<>(byTimeDesc);

        for (String rev : startRevisions) {
            String oid = Revision.parse(rev.trim()).getCommitId(repo);
            db.loadCommit(oid);
            processQueue.add(oid);
            mark(oid, CommitFlag.SEEN, flags);
        }

        if (!Iterables.isEmpty(excludeRevisions)) {
            for (String rev : excludeRevisions) {
                String oid = Revision.parse(rev.trim()).getCommitId(repo);
                db.loadCommit(oid);
                processQueue.add(oid);
                mark(oid, CommitFlag.SEEN, flags);
                mark(oid, CommitFlag.UNINTERESTING, flags);
            }
        }

        while (!processQueue.isEmpty()) {
            String oid = processQueue.poll();
            Commit commit = db.loadCommit(oid);
            if (commit == null) {
                continue;
            }
            String parentCommitId = commit.getParentOid();
            if (parentCommitId != null) {
                db.loadCommit(parentCommitId);
                mark(parentCommitId, CommitFlag.SEEN, flags);
                processQueue.add(parentCommitId);
            }
            if (marked(oid, CommitFlag.UNINTERESTING, flags) && parentCommitId != null) {
                mark(parentCommitId, CommitFlag.UNINTERESTING, flags);
            }
            if (!marked(oid, CommitFlag.UNINTERESTING, flags)) {
                logQueue.add(oid);
            }

            if (!logQueue.isEmpty()) {
                String logCommitId = logQueue.poll();
                Commit c = db.loadCommit(logCommitId);
                if (c != null) {
                    consumer.accept(new CommitEntry(logCommitId, c));
                }
            }
        }

        while (!logQueue.isEmpty()) {
            String logCommitId = logQueue.poll();
            Commit c = db.loadCommit(logCommitId);
            if (c != null) {
                consumer.accept(new CommitEntry(logCommitId, c));
            }
        }
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
    public static final class CommitEntry {

        private final String oid;
        private final Commit commit;

        public CommitEntry(String oid, Commit commit) {
            this.oid = oid;
            this.commit = commit;
        }

        public String oid() {
            return oid;
        }

        public Commit commit() {
            return commit;
        }
    }
}
