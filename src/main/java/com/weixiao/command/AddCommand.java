package com.weixiao.command;

import com.weixiao.obj.Blob;
import com.weixiao.repo.Index;
import com.weixiao.repo.Workspace;
import com.weixiao.utils.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * jit add - 将工作区中的文件或目录加入暂存区，支持一次添加多个路径。
 * 文件会生成 blob 并记录 path/mode/oid；目录会递归添加其下所有文件。
 */
@Command(name = "add", mixinStandardHelpOptions = true, description = "将文件或目录加入暂存区")
public class AddCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(AddCommand.class);

    /**
     * 多值参数分隔符（路径中一般不包含）。
     */
    private static final String PATHS_SEP = "\n";

    /**
     * 要添加的路径（文件或目录），可多个。
     */
    @SuppressWarnings({"unused", "MismatchedQueryAndUpdateOfCollection"})
    @Parameters(index = "0", arity = "1..*", paramLabel = "PATH", description = "要添加的文件或目录路径（可多个）")
    private List<Path> paths;

    @Override
    protected void initParams() {
        params = new LinkedHashMap<>();
        if (paths != null && !paths.isEmpty()) {
            params.put("paths", paths.stream().map(Path::toString).collect(Collectors.joining(PATHS_SEP)));
        }
    }

    @Override
    protected void doRun() {
        log.debug("add start path={}", getStartPath());
        Path root = repo.getRoot();
        log.debug("repo root={}", root);

        try {
            repo.getIndex().load();

            String pathsStr = get("paths");
            List<Path> pathList = pathsStr == null || pathsStr.isEmpty()
                    ? Collections.emptyList()
                    : Arrays.stream(pathsStr.split(PATHS_SEP)).map(Paths::get).toList();
            Path start = getStartPath();
            for (Path p : pathList) {
                Path resolved = start.resolve(p).normalize();
                if (!Files.exists(resolved)) {
                    log.warn("path not found: {}", resolved);
                    System.err.println("fatal: path not found: " + p);
                    exitCode = 1;
                    continue;
                }
                if (!resolved.startsWith(root)) {
                    log.warn("path outside repo: {}", resolved);
                    System.err.println("fatal: path outside repository: " + p);
                    exitCode = 1;
                    continue;
                }
                addPath(root, resolved);
            }

            repo.getIndex().save();
            log.info("add completed, index entries={}", repo.getIndex().getEntries().size());
        } catch (IOException e) {
            log.error("add failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    /**
     * 将单个路径（文件或目录）加入暂存区。
     * 文件：读内容生成 blob，取 mode，写入 index。
     * 目录：递归收集所有普通文件，逐个添加。
     */
    private void addPath(Path root, Path resolved) throws IOException {
        if (Files.isRegularFile(resolved)) {
            addFile(root, resolved);
        } else if (Files.isDirectory(resolved)) {
            addDirectory(root, resolved);
        }
    }

    /**
     * 添加单个文件到暂存区，使用文件真实 stat（ctime/mtime/dev/ino/uid/gid）。
     * todo 考虑删除文件的情况，这时候执行add命令，其实是将对应删除的文件从index中移除
     */
    private void addFile(Path root, Path filePath) throws IOException {
        String relative = PathUtils.normalizePath(root.relativize(filePath));
        byte[] data = repo.getWorkspace().readFile(filePath);
        Blob blob = new Blob(data);
        String blobOid = repo.getDatabase().store(blob);
        String mode = Workspace.getFileMode(filePath);
        Index.IndexStat stat = Workspace.getFileStat(filePath);
        repo.getIndex().add(new Index.Entry(relative, mode, blobOid, 0, data.length, stat));
        log.debug("added file {} -> {} mode={} size={} stat={}", relative, blobOid, mode, data.length, stat);
    }

    /**
     * 递归添加目录下所有普通文件。
     */
    private void addDirectory(Path root, Path dirPath) throws IOException {
        List<Path> children = Workspace.listEntries(dirPath);
        for (Path child : children) {
            if (Files.isRegularFile(child)) {
                addFile(root, child);
            } else if (Files.isDirectory(child)) {
                addDirectory(root, child);
            }
        }
    }

}
