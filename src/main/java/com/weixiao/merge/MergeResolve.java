package com.weixiao.merge;

import com.weixiao.repo.Migration;

import java.io.IOException;

/**
 * Merge 解析与执行：基于 Inputs，把 base->merge 的净变化应用到 workspace 与 index。
 */
public final class MergeResolve {

    private final MergeInputs inputs;

    public MergeResolve(MergeInputs inputs) {
        this.inputs = inputs;
    }

    public void execute() throws IOException {
        Migration migration = new Migration(inputs.getBaseOid(), inputs.getMergeOid());
        migration.validate();
        migration.applyChanges();
    }
}

