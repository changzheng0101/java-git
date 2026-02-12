package com.weixiao.command;

import com.weixiao.obj.Blob;
import com.weixiao.repo.Index;
import com.weixiao.repo.Repository;
import com.weixiao.repo.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * jit add - 将工作区中的文件或目录加入暂存区，支持一次添加多个路径。
 * 文件会生成 blob 并记录 path/mode/oid；目录会递归添加其下所有文件。
 */
@Command(name = "add", mixinStandardHelpOptions = true, description = "将文件或目录加入暂存区")
public class AddCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(AddCommand.class);

    /**
     * 要添加的路径（文件或目录），可多个。
     */
    @Parameters(index = "0", arity = "1..*", paramLabel = "PATH", description = "要添加的文件或目录路径（可多个）")
    private List<Path> paths;

    /**
     * 仓库根路径，默认为当前目录。
     */
    @Option(names = {"-C", "--path"}, paramLabel = "DIR", description = "仓库根路径，默认为当前目录")
    private Path path;

    private int exitCode = 0;

    @Override
    public void run() {
        exitCode = 0;
        Path start = path != null ? path.toAbsolutePath().normalize() : Paths.get("").toAbsolutePath().normalize();
        log.debug("add start path={}", start);

        Repository repo = Repository.find(start);
        if (repo == null) {
            log.debug("no repo found from {}", start);
            log.info("add aborted: not a jit repository");
            System.err.println("fatal: not a jit repository (or any of the parent directories): .git");
            exitCode = 1;
            return;
        }
        Path root = repo.getRoot();
        log.debug("repo root={}", root);

        try {
            repo.getIndex().load();

            for (Path p : paths) {
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
                addPath(repo, root, resolved);
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
    private void addPath(Repository repo, Path root, Path resolved) throws IOException {
        if (Files.isRegularFile(resolved)) {
            addFile(repo, root, resolved);
        } else if (Files.isDirectory(resolved)) {
            addDirectory(repo, root, resolved);
        }
    }

    /**
     * 添加单个文件到暂存区，使用文件真实 stat（ctime/mtime/dev/ino/uid/gid）。
     */
    private void addFile(Repository repo, Path root, Path filePath) throws IOException {
        String relative = root.relativize(filePath).toString().replace('\\', '/');
        byte[] data = repo.getWorkspace().readFile(filePath);
        Blob blob = new Blob(data);
        String blobOid = repo.getDatabase().store(blob);
        String mode = repo.getWorkspace().getFileMode(filePath);
        Index.IndexStat stat = Workspace.getFileStat(filePath);
        repo.getIndex().add(relative, mode, blobOid, data.length, stat);
        log.debug("added file {} -> {} mode={} size={} stat={}", relative, blobOid, mode, data.length, stat);
    }

    /**
     * 递归添加目录下所有普通文件。
     */
    private void addDirectory(Repository repo, Path root, Path dirPath) throws IOException {
        List<Path> children = repo.getWorkspace().listEntries(dirPath);
        for (Path child : children) {
            if (Files.isRegularFile(child)) {
                addFile(repo, root, child);
            } else if (Files.isDirectory(child)) {
                addDirectory(repo, root, child);
            }
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
