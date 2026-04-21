package com.weixiao.repo;

import com.weixiao.diff.DiffEntry;
import com.weixiao.obj.GitObject;
import com.weixiao.utils.PathUtils;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 工作区：列出并读取工作目录中的文件（排除 .、..、.git）。
 * 可以直接和文件系统进行交互
 */
@Data
@NoArgsConstructor
public final class Workspace {

    private static final Logger log = LoggerFactory.getLogger(Workspace.class);

    private static final String GIT_DIR = ".git";
    private Path root;

    /**
     * 以给定路径为工作区根目录。
     */
    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }


    /**
     * 读取工作区根目录下名为 name 的文件的全部字节。
     */
    @SuppressWarnings("unused")
    public byte[] readFile(String name) throws IOException {
        return Files.readAllBytes(root.resolve(name));
    }

    /**
     * 读取指定路径的文件的全部字节。
     */
    public byte[] readFile(Path filePath) throws IOException {
        return Files.readAllBytes(filePath);
    }

    /**
     * 检查工作区根目录下名为 name 的文件是否可执行。
     * 在 Unix/Linux/macOS 上检查文件权限位，在 Windows 上检查文件扩展名。
     */
    @SuppressWarnings("unused")
    public boolean isExecutable(String name) {
        return Files.isExecutable(root.resolve(name));
    }

    /**
     * 获取文件的 Git mode 字符串（如 "100644" 或 "100755"）。
     * mode 格式：第一位为文件类型（1=regular file），后三位从文件权限读取。
     * 在 Windows 上，如果无法获取 POSIX 权限，则根据是否可执行返回默认值。
     */
    @SuppressWarnings("unused")
    public String getFileMode(String name) throws IOException {
        Path filePath = root.resolve(name);
        return getFileMode(filePath);
    }

    /**
     * 获取指定路径的 Git mode 字符串。
     * 对于目录返回 "40000"，对于文件返回基于权限的 mode。
     */
    public static String getFileMode(Path filePath) throws IOException {
        if (Files.isDirectory(filePath)) {
            return "40000";
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(filePath);
            int ownerPerm = permissionToOctal(permissions, PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            int groupPerm = permissionToOctal(permissions, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE);
            int othersPerm = permissionToOctal(permissions, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
            String mode = String.format("100%d%d%d", ownerPerm, groupPerm, othersPerm);
            log.debug("getFileMode {} -> {}", filePath, mode);
            return mode;
        } catch (UnsupportedOperationException e) {
            // Windows 或其他不支持 POSIX 权限的系统
            boolean executable = Files.isExecutable(filePath);
            String mode = executable ? "100755" : "100644";
            log.debug("getFileMode {} -> {} (fallback, executable={})", filePath, mode, executable);
            return mode;
        }
    }

    /**
     * 获取文件的 stat 属性，用于写入 index（ctime、mtime、dev、ino、uid、gid）。
     * 在 Unix 上尽量使用真实值；不支持时（如 Windows）对应字段为 0。
     */
    public static Index.IndexStat getFileStat(Path filePath) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
        FileTime creationTime = attrs.creationTime();
        FileTime lastModifiedTime = attrs.lastModifiedTime();
        long ctimeSec = creationTime.toInstant().getEpochSecond();
        int ctimeNsec = creationTime.toInstant().getNano();
        long mtimeSec = lastModifiedTime.toInstant().getEpochSecond();
        int mtimeNsec = lastModifiedTime.toInstant().getNano();
        int dev = 0, ino = 0, uid = 0, gid = 0;
        try {
            Object devObj = Files.getAttribute(filePath, "unix:dev");
            Object inoObj = Files.getAttribute(filePath, "unix:ino");
            Object uidObj = Files.getAttribute(filePath, "unix:uid");
            Object gidObj = Files.getAttribute(filePath, "unix:gid");
            if (devObj instanceof Number) dev = ((Number) devObj).intValue();
            if (inoObj instanceof Number) ino = ((Number) inoObj).intValue();
            if (uidObj instanceof Number) uid = ((Number) uidObj).intValue();
            if (gidObj instanceof Number) gid = ((Number) gidObj).intValue();
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            log.debug("unix stat not available for {}: {}", filePath, e.getMessage());
        }
        return new Index.IndexStat(
                (int) (ctimeSec & 0xFFFFFFFFL), ctimeNsec,
                (int) (mtimeSec & 0xFFFFFFFFL), mtimeNsec,
                dev, ino, uid, gid);
    }

    /**
     * 列出指定目录下的所有条目（文件和子目录），排除 .git。
     */
    public static List<Path> listEntries(Path basePath) throws IOException {
        List<Path> entries = new ArrayList<>();
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            return entries;
        }
        try (Stream<Path> stream = Files.list(basePath)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (GIT_DIR.equals(p.getFileName().toString())) {
                    continue;
                }
                entries.add(p);
            }
        }
        return entries;
    }

    /**
     * 将 POSIX 权限转换为八进制数字（0-7）。
     * 参数为 owner/group/others 的 read/write/execute 权限。
     */
    private static int permissionToOctal(Set<PosixFilePermission> permissions,
                                         PosixFilePermission read,
                                         PosixFilePermission write,
                                         PosixFilePermission execute) {
        int value = 0;
        if (permissions.contains(read)) value |= 4;
        if (permissions.contains(write)) value |= 2;
        if (permissions.contains(execute)) value |= 1;
        return value;
    }

    /**
     * 工作区根目录路径。
     */
    @SuppressWarnings("unused")
    public Path getRoot() {
        return root;
    }

    /**
     * 将内容写入工作区相对路径；先创建父目录再写入，并根据 mode 设置可执行位（100755）。
     */
    public void writeFile(String relativePath, byte[] content, String mode) throws IOException {
        Path filePath = root.resolve(PathUtils.normalizePath(relativePath)).normalize();
        if (!filePath.startsWith(root)) {
            throw new IOException("path escapes workspace: " + relativePath);
        }
        Path parent = filePath.getParent();
        if (parent != null) {
            ensureParentDirectories(parent);
        }
        Files.write(filePath, content != null ? content : new byte[0]);
        if ("100755".equals(mode)) {
            try {
                Files.setPosixFilePermissions(filePath, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException e) {
                log.debug("setPosixFilePermissions not supported for {}", filePath);
            }
        }
        log.debug("writeFile path={} size={} mode={}", relativePath, content != null ? content.length : 0, mode);
    }

    /**
     * 确保父目录链存在；若父路径上已存在同名文件，则先删除该文件并创建目录（记录 warn）。
     */
    private void ensureParentDirectories(Path parent) throws IOException {
        Path relativeParent = root.relativize(parent);
        Path current = root;
        for (Path name : relativeParent) {
            current = current.resolve(name).normalize();
            if (!Files.exists(current)) {
                Files.createDirectory(current);
                continue;
            }
            if (Files.isDirectory(current)) {
                continue;
            }

            String conflictedPath = PathUtils.normalizePath(root.relativize(current));
            log.warn("path {} is a file, delete and recreate as directory", conflictedPath);
            Files.delete(current);
            Files.createDirectory(current);
        }
    }

    /**
     * 删除工作区中的文件或空目录；非空目录会抛出异常，调用方需先按子路径先删的顺序调用。
     */
    public void deletePath(String relativePath) throws IOException {
        Path path = root.resolve(PathUtils.normalizePath(relativePath)).normalize();
        if (!path.startsWith(root)) {
            throw new IOException("path escapes workspace: " + relativePath);
        }
        if (!Files.exists(path)) {
            log.debug("deletePath path={} (already absent)", relativePath);
            return;
        }
        Files.delete(path);
        log.debug("deletePath path={}", relativePath);
    }

    /**
     * 递归删除路径（文件或目录，类似 rm -rf）；不进入 .git。
     */
    public void rm_rf(String relativePath) throws IOException {
        Path path = root.resolve(PathUtils.normalizePath(relativePath)).normalize();
        if (!path.startsWith(root)) {
            throw new IOException("path escapes workspace: " + relativePath);
        }
        if (!Files.exists(path)) {
            return;
        }
        rm_rfRecursive(path);
    }

    private void rm_rfRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            List<Path> children;
            try (java.util.stream.Stream<Path> stream = Files.list(path)) {
                children = stream.toList();
            }
            for (Path child : children) {
                if (GIT_DIR.equals(child.getFileName().toString())) {
                    continue;
                }
                rm_rfRecursive(child);
            }
            Files.delete(path);
        } else {
            Files.delete(path);
        }
    }

    /**
     * 应用一次迁移到工作区：先按删除列表 rm_rf，再按 rmdirs 删空目录（先子后父），再按 mkdirs 建目录（先父后子），最后写创建/修改的文件。
     */
    public void applyMigration(Migration m) throws IOException {
        ObjectDatabase db = Repository.INSTANCE.getDatabase();

        for (DiffEntry e : m.getDeletes()) {
            if (e.getEntryA() != null && !e.getEntryA().isDirectory()) {
                rm_rf(PathUtils.normalizePath(e.getPath()));
            }
        }

        // 删除delete操作执行完之后的空文件夹
        List<String> rmdirsSorted = new ArrayList<>(m.getRmdirs());
        rmdirsSorted.sort(Comparator.comparingInt((String s) -> PathUtils.pathDepth(s)).reversed().thenComparing(s -> s));
        for (String dir : rmdirsSorted) {
            Path p = root.resolve(dir);
            if (Files.exists(p) && Files.isDirectory(p)) {
                List<Path> paths = Workspace.listEntries(p);
                if (paths.stream().findAny().isEmpty()) {
                    deletePath(dir);
                }
            }
        }

        // 创建必要的文件夹
        List<String> mkdirsSorted = new ArrayList<>(m.getMkdirs());
        mkdirsSorted.sort(Comparator.comparingInt((String s) -> PathUtils.pathDepth(s)).thenComparing(s -> s));
        for (String dir : mkdirsSorted) {
            Path p = root.resolve(dir);
            if (!Files.exists(p)) {
                Files.createDirectories(p);
            }
        }

        for (DiffEntry e : m.getCreates()) {
            writeEntry(db, e);
        }
        for (DiffEntry e : m.getModifies()) {
            writeEntry(db, e);
        }
    }

    private void writeEntry(ObjectDatabase db, DiffEntry e) throws IOException {
        String path = PathUtils.normalizePath(e.getPath());
        String oid = e.getEntryB().getOid();
        String mode = e.getEntryB().getMode();
        GitObject obj = db.load(oid);
        if (!"blob".equals(obj.getType())) {
            throw new IOException("expected blob for path " + path + ", got " + obj.getType());
        }
        byte[] content = obj.toBytes();
        writeFile(path, content, mode);
    }
}
