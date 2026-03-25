package com.weixiao.repo;

import com.weixiao.diff.DiffEntry;
import com.weixiao.diff.TreeDiff;
import com.weixiao.obj.TreeEntry;
import com.weixiao.utils.PathUtils;
import lombok.Getter;
import lombok.SneakyThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次迁移：对 workspace 和 index 的变更进行合法性检查后应用。
 * 先计划（按删除、创建、修改分组并排序），再执行：先更新 workspace（先删后建，删除先子后父，创建先父后子），
 * 再按目标 commit 状态更新 index，最后更新 HEAD。
 */
@Getter
public final class Migration {

    private String currentCommitId;
    private String targetCommitOid;

    /**
     * 可能需要删除的目录路径（删除文件中产生的空父目录候选），由 planChanges 填充。
     */
    private final Set<String> rmdirs = new HashSet<>();
    /**
     * 需要确保存在的目录路径（创建/修改文件前需存在的父目录），由 planChanges 填充。
     */
    private final Set<String> mkdirs = new HashSet<>();


    private final List<DiffEntry> changes;
    private List<DiffEntry> deletes = new ArrayList<>();
    private List<DiffEntry> creates = new ArrayList<>();
    private List<DiffEntry> modifies = new ArrayList<>();

    /**
     * @param currentCommitId 当前 commit oid（如 HEAD），不能为空
     * @param targetCommitOid 目标 commit oid，用于 diff、填充 index 与更新 HEAD
     */
    @SneakyThrows
    public Migration(String currentCommitId, String targetCommitOid) {
        this.currentCommitId = currentCommitId;
        this.targetCommitOid = targetCommitOid;
        this.changes = TreeDiff.diff(currentCommitId, targetCommitOid);
    }

    public Migration(List<DiffEntry> changes) {
        this.changes = changes;
    }

    /**
     * 将变更应用到仓库：先计划，再更新 workspace，再更新 index，最后更新 HEAD。
     */
    public void applyChanges() throws IOException {
        planChanges();
        updateWorkspace();
        updateIndex();
    }

    /**
     * 检查对 workspace 和 index 的变更是否合法，由外界在 applyChanges 前调用，先留空。
     */
    public void validate() {
        // TODO: 检查状态是否合法
    }

    /**
     * 计划变更：拉取 diff，按删除/创建/修改分组排序，并填充 rmdirs、mkdirs。
     * 删除类变更：将对应文件的全部父目录加入 rmdirs，作为后续可删除的空目录候选。
     * 创建与修改：将对应文件的父目录加入 mkdirs，确保写文件前目录存在。
     */
    public void planChanges() {

        rmdirs.clear();
        mkdirs.clear();
        deletes = new ArrayList<>();
        creates = new ArrayList<>();
        modifies = new ArrayList<>();
        for (DiffEntry c : changes) {
            switch (c.getStatus()) {
                case DELETED:
                    rmdirs.addAll(PathUtils.getAllParentDir(c.getPath().toString()));
                    deletes.add(c);
                    break;
                case CREATED:
                    mkdirs.addAll(PathUtils.getAllParentDir(c.getPath().toString()));
                    creates.add(c);
                    break;
                case MODIFIED:
                    mkdirs.addAll(PathUtils.getAllParentDir(c.getPath().toString()));
                    modifies.add(c);
                    break;
                default:
                    break;
            }
        }

        deletes.sort(Comparator
                .comparingInt((DiffEntry c) -> PathUtils.pathDepth(c.getPath()))
                .reversed()
                .thenComparing(c -> PathUtils.normalizePath(c.getPath())));
        Comparator<DiffEntry> byDepthAsc = Comparator
                .comparingInt((DiffEntry c) -> PathUtils.pathDepth(c.getPath()))
                .thenComparing(c -> PathUtils.normalizePath(c.getPath()));
        creates.sort(byDepthAsc);
        modifies.sort(byDepthAsc);
    }

    /**
     * 根据 planChanges 结果更新工作区：委托给 Workspace.applyMigration。
     * 只有Workspace才能真正的操作文件
     */
    public void updateWorkspace() throws IOException {
        Repository.INSTANCE.getWorkspace().applyMigration(this);
    }

    /**
     * 根据 deletes、creates、modifies 更新 index：对删除项执行 remove，对新增/修改项执行 add。
     */
    public void updateIndex() throws IOException {
        Repository repo = Repository.INSTANCE;
        Index index = repo.getIndex();
        java.nio.file.Path root = repo.getRoot();

        index.load();
        applyIndexChanges(index, root);

        index.save();
    }

    private void applyIndexChanges(Index index, Path root) throws IOException {
        for (DiffEntry e : deletes) {
            if (e.getEntryA() != null && !e.getEntryA().isDirectory()) {
                index.remove(PathUtils.normalizePath(e.getPath().toString()));
            }
        }

        for (DiffEntry e : creates) {
            if (e.getEntryB() != null && !e.getEntryB().isDirectory()) {
                Path filePath = root.resolve(e.getPath());
                TreeEntry newEntry = e.getEntryB();
                index.add(PathUtils.normalizePath(e.getPath()), newEntry.getMode(), newEntry.getOid(), (int) Files.size(filePath), Workspace.getFileStat(filePath));
            }
        }
        for (DiffEntry e : modifies) {
            if (e.getEntryB() != null && !e.getEntryB().isDirectory()) {
                Path filePath = root.resolve(e.getPath());
                TreeEntry newEntry = e.getEntryB();
                index.add(PathUtils.normalizePath(e.getPath()), newEntry.getMode(), newEntry.getOid(), (int) Files.size(filePath), Workspace.getFileStat(filePath));
            }
        }
    }
}
