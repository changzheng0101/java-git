package com.weixiao.merge;

import com.weixiao.revision.Revision;
import com.weixiao.repo.Repository;

import java.io.IOException;

/**
 * Merge 命令输入的归一化表示：解析 HEAD 与命令参数，确定 head/merge/base 三个提交 oid。
 */
public final class MergeInputs {

    public enum Kind {
        UP_TO_DATE,
        FAST_FORWARD,
        MERGE
    }

    private final Kind kind;
    private final String headOid;
    private final String mergeOid;
    private final String baseOid;

    private MergeInputs(Kind kind, String headOid, String mergeOid, String baseOid) {
        this.kind = kind;
        this.headOid = headOid;
        this.mergeOid = mergeOid;
        this.baseOid = baseOid;
    }

    public static MergeInputs from(String rev) throws IOException {
        Repository repo = Repository.INSTANCE;
        String headOid = repo.getRefs().readHead();
        if (headOid == null || headOid.isEmpty()) {
            return new MergeInputs(null, null, null, null);
        }

        String mergeTipOid = Revision.parse(rev).getCommitId(repo);
        if (headOid.equals(mergeTipOid) || CommonAncestors.isAncestor(mergeTipOid, headOid)) {
            return new MergeInputs(Kind.UP_TO_DATE, headOid, mergeTipOid, null);
        }
        if (CommonAncestors.isAncestor(headOid, mergeTipOid)) {
            return new MergeInputs(Kind.FAST_FORWARD, headOid, mergeTipOid, null);
        }

        String baseOid = CommonAncestors.findBestCommonAncestor(headOid, mergeTipOid);
        return new MergeInputs(Kind.MERGE, headOid, mergeTipOid, baseOid);
    }

    public Kind getKind() {
        return kind;
    }

    public String getHeadOid() {
        return headOid;
    }

    public String getMergeOid() {
        return mergeOid;
    }

    public String getBaseOid() {
        return baseOid;
    }
}

