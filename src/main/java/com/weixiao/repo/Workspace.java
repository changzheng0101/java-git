package com.weixiao.repo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 工作区：列出并读取工作目录中的文件（排除 .、..、.git）。
 */
public final class Workspace {

    private static final Logger log = LoggerFactory.getLogger(Workspace.class);

    private static final String GIT_DIR = ".git";
    private final Path root;

    /**
     * 以给定路径为工作区根目录。
     */
    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 列出根目录下的普通文件（不含目录、不含 .git）。
     * 简化实现：仅一层，不递归子目录。
     * @deprecated 使用 listEntries() 支持递归目录
     */
    @Deprecated
    public List<String> listFiles() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (GIT_DIR.equals(name)) continue;
                names.add(name);
            }
        }
        log.debug("listFiles root={} count={} names={}", root, names.size(), names);
        return names;
    }

    /**
     * 列出指定目录下的所有条目（文件和子目录），排除 .git。
     * 返回相对于 basePath 的路径列表。
     */
    public List<Path> listEntries(Path basePath) throws IOException {
        List<Path> entries = new ArrayList<>();
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            return entries;
        }
        try (Stream<Path> stream = Files.list(basePath)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (GIT_DIR.equals(name)) continue;
                entries.add(p);
            }
        }
        log.debug("listEntries basePath={} count={}", basePath, entries.size());
        return entries;
    }

    /**
     * 读取工作区根目录下名为 name 的文件的全部字节。
     */
    public byte[] readFile(String name) throws IOException {
        return Files.readAllBytes(root.resolve(name));
    }

    /** 读取指定路径的文件的全部字节。 */
    public byte[] readFile(Path filePath) throws IOException {
        return Files.readAllBytes(filePath);
    }

    /**
     * 检查工作区根目录下名为 name 的文件是否可执行。
     * 在 Unix/Linux/macOS 上检查文件权限位，在 Windows 上检查文件扩展名。
     */
    public boolean isExecutable(String name) {
        return Files.isExecutable(root.resolve(name));
    }

    /**
     * 获取文件的 Git mode 字符串（如 "100644" 或 "100755"）。
     * mode 格式：第一位为文件类型（1=regular file），后三位从文件权限读取。
     * 在 Windows 上，如果无法获取 POSIX 权限，则根据是否可执行返回默认值。
     */
    public String getFileMode(String name) throws IOException {
        Path filePath = root.resolve(name);
        return getFileMode(filePath);
    }

    /**
     * 获取指定路径的 Git mode 字符串。
     * 对于目录返回 "40000"，对于文件返回基于权限的 mode。
     */
    public String getFileMode(Path filePath) throws IOException {
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
    public Path getRoot() {
        return root;
    }
}
