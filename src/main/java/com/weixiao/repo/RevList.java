package com.weixiao.repo;

import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.revision.Revision;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 类似 git rev-list：从给定 revision 开始沿 parent 链遍历，找出符合条件的提交。
 * 不负责格式化或输出，仅返回 (oid, Commit) 列表，供 log 等上层命令使用。
 */
public final class RevList {

    private RevList() {
    }

    /**
     * 从给定 revision（如 HEAD、分支名、oid）开始沿 parent 链遍历，返回提交列表。
     * 遇非 commit 对象则停止。仓库通过 {@link Repository#INSTANCE} 单例获取。
     *
     * @param revision 修订说明（HEAD、master、oid 等）
     * @return 按遍历顺序的 (oid, Commit) 列表
     */
    public static List<CommitEntry> walk(String revision) throws IOException {
        Repository repo = Repository.INSTANCE;
        String oid = Revision.parse(revision).getCommitId(repo);
        List<CommitEntry> result = new ArrayList<>();
        while (oid != null) {
            GitObject obj = repo.getDatabase().load(oid);
            if (!"commit".equals(obj.getType())) {
                break;
            }
            Commit commit = Commit.fromBytes(obj.toBytes());
            result.add(new CommitEntry(oid, commit));
            oid = commit.getParentOid();
        }
        return result;
    }

    /**
     * rev-list 遍历得到的一条记录：commit oid + 解析后的 Commit。
     */
    public record CommitEntry(String oid, Commit commit) {
    }
}
