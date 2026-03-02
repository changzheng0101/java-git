package com.weixiao.repo;

import com.weixiao.diff.DiffEntry;
import com.weixiao.obj.GitObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次迁移：对 workspace 和 index 的变更进行合法性检查后应用。
 * 先更新 workspace（先删除后创建/修改，删除时先子后父，创建时先父后子），再按目标 commit 状态更新 index，最后更新 HEAD。
 */
public final class Migration {

    private static final Logger log = LoggerFactory.getLogger(Migration.class);

    private final List<DiffEntry> changes;
    private final List<DiffEntry> targetIndexEntries;
    private final String targetCommitOid;

    public Migration(List<DiffEntry> changes, List<DiffEntry> targetIndexEntries, String targetCommitOid) {
        this.changes = changes != null ? new ArrayList<>(changes) : new ArrayList<>();
        this.targetIndexEntries = targetIndexEntries != null ? new ArrayList<>(targetIndexEntries) : new ArrayList<>();
        this.targetCommitOid = targetCommitOid;
    }

    /**
     * 检查对 workspace 和 index 的变更是否合法，先留空。
     */
    public void validate() {
        // TODO: 检查状态是否合法
    }

    /**
     * 将变更应用到仓库：先 workspace（先删后建），再 index，最后 HEAD。
     */
    public void applyChanges(Repository repo) throws IOException {
        validate();

        List<DiffEntry> deletes = new ArrayList<>();
        List<DiffEntry> creates = new ArrayList<>();
        List<DiffEntry> modifies = new ArrayList<>();
        for (DiffEntry c : changes) {
            switch (c.getStatus()) {
                case DELETED:
                    deletes.add(c);
                    break;
                case CREATED:
                    creates.add(c);
                    break;
                case MODIFIED:
                    modifies.add(c);
                    break;
                default:
                    break;
            }
        }

        // 删除：先子路径后父路径（深度从大到小）
        deletes.sort(Comparator
            .comparingInt((DiffEntry c) -> pathDepth(c.getPath()))
            .reversed()
            .thenComparing(DiffEntry::getPath));

        // 创建与修改：先父路径后子路径（深度从小到大）
        Comparator<DiffEntry> byDepthAsc = Comparator
            .comparingInt((DiffEntry c) -> pathDepth(c.getPath()))
            .thenComparing(DiffEntry::getPath);
        creates.sort(byDepthAsc);
        modifies.sort(byDepthAsc);

        Workspace ws = repo.getWorkspace();
        ObjectDatabase db = repo.getDatabase();

        for (DiffEntry c : deletes) {
            ws.deletePath(c.getPath());
        }
        removeEmptyParentDirs(repo, deletes);
        for (DiffEntry c : creates) {
            applyCreateOrModify(ws, db, c);
        }
        for (DiffEntry c : modifies) {
            applyCreateOrModify(ws, db, c);
        }

        applyIndex(repo);
        repo.getRefs().updateMaster(targetCommitOid);
        log.info("migration applied, HEAD -> {}", targetCommitOid);
    }

    private static void removeEmptyParentDirs(Repository repo, List<DiffEntry> deletes) throws IOException {
        Set<String> parentDirs = new HashSet<>();
        for (DiffEntry c : deletes) {
            String p = c.getPath();
            int i = p.lastIndexOf('/');
            while (i > 0) {
                parentDirs.add(p.substring(0, i));
                i = p.lastIndexOf('/', i - 1);
            }
        }
        List<String> sorted = new ArrayList<>(parentDirs);
        sorted.sort(Comparator.comparingInt(Migration::pathDepth).reversed().thenComparing(s -> s));
        for (String dir : sorted) {
            java.nio.file.Path path = repo.getRoot().resolve(dir.replace('\\', '/'));
            if (Files.exists(path) && Files.isDirectory(path)) {
                try {
                    if (!Files.list(path).iterator().hasNext()) {
                        repo.getWorkspace().deletePath(dir);
                    }
                } catch (IOException ignored) {
                    // 非空则跳过
                }
            }
        }
    }

    private static int pathDepth(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                n++;
            }
        }
        return n;
    }

    private static void applyCreateOrModify(Workspace ws, ObjectDatabase db, DiffEntry c) throws IOException {
        String oid = c.getEntryB().getOid();
        String mode = c.getEntryB().getMode();
        GitObject obj = db.load(oid);
        if (!"blob".equals(obj.getType())) {
            throw new IOException("expected blob for path " + c.getPath() + ", got " + obj.getType());
        }
        byte[] content = obj.toBytes();
        ws.writeFile(c.getPath(), content, mode);
    }

    private void applyIndex(Repository repo) throws IOException {
        repo.getIndex().load();
        repo.getIndex().clear();
        ObjectDatabase db = repo.getDatabase();
        Index index = repo.getIndex();

        java.nio.file.Path root = repo.getRoot();
        for (DiffEntry e : targetIndexEntries) {
            String path = e.getPath();
            String oid = e.getEntryB().getOid();
            String mode = e.getEntryB().getMode();
            GitObject obj = db.load(oid);
            if (!"blob".equals(obj.getType())) {
                continue;
            }
            int size = obj.toBytes().length;
            java.nio.file.Path filePath = root.resolve(path.replace('\\', '/'));
            Index.IndexStat stat = Workspace.getFileStat(filePath);
            index.add(path, mode, oid, size, stat);
        }
        index.save();
    }
}
