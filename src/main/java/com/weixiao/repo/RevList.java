package com.weixiao.repo;

import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.revision.Revision;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 类似 git rev-list：从给定 revision 开始沿 parent 链遍历，找出符合条件的提交。
 * 支持多个 revision，合并历史、按提交时间“最晚优先”遍历，每个 commit 只输出一次。
 * 不负责格式化或输出，仅返回 (oid, Commit) 列表，供 log 等上层命令使用。
 */
public final class RevList {

    private RevList() {
    }

    /** 已访问（已输出）标记。 */
    public static final String FLAG_VISITED = "visited";

    /**
     * 从多个 revision 出发，合并遍历所有可达提交，按提交时间从新到旧，每个 commit 只出现一次。
     * 无参数时等价于 walk("HEAD")。仓库通过 {@link Repository#INSTANCE} 单例获取。
     *
     * @param revisions 修订说明（HEAD、分支名、oid 等），可为空表示默认 HEAD
     * @return 按提交时间从新到旧的 (oid, Commit) 列表
     */
    public static List<CommitEntry> walk(String... revisions) throws IOException {
        Repository repo = Repository.INSTANCE;
        List<String> revList = revisions != null && revisions.length > 0
                ? List.of(revisions)
                : List.of("HEAD");

        Map<String, Commit> commits = new HashMap<>();
        Set<String> flags = new HashSet<>();
        Comparator<String> byTimeDesc = (a, b) -> {
            long ta = Commit.getAuthorTimestamp(commits.get(a).getAuthor());
            long tb = Commit.getAuthorTimestamp(commits.get(b).getAuthor());
            int c = Long.compare(tb, ta);
            return c != 0 ? c : a.compareTo(b);
        };
        PriorityQueue<String> queue = new PriorityQueue<>(byTimeDesc);

        for (String rev : revList) {
            String oid = Revision.parse(rev.trim()).getCommitId(repo);
            if (loadCommit(repo, oid, commits)) {
                queue.add(oid);
            }
        }

        List<CommitEntry> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String oid = queue.poll();
            if (marked(oid, FLAG_VISITED, flags)) {
                continue;
            }
            mark(oid, FLAG_VISITED, flags);
            Commit commit = commits.get(oid);
            result.add(new CommitEntry(oid, commit));

            String parentOid = commit.getParentOid();
            if (parentOid != null && loadCommit(repo, parentOid, commits)) {
                queue.add(parentOid);
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
    public static boolean mark(String oid, String flag, Set<String> flags) {
        String key = oid + ":" + flag;
        return flags.add(key);
    }

    /**
     * 检查 commit 是否已有某标记。
     */
    public static boolean marked(String oid, String flag, Set<String> flags) {
        return flags.contains(oid + ":" + flag);
    }

    /**
     * rev-list 遍历得到的一条记录：commit oid + 解析后的 Commit。
     */
    public record CommitEntry(String oid, Commit commit) {
    }
}
