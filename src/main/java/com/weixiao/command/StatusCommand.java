package com.weixiao.command;

import com.weixiao.repo.Repository;
import com.weixiao.repo.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * jit status - 显示工作区状态，当前支持列出未跟踪文件（untracked files）。
 */
@Command(name = "status", mixinStandardHelpOptions = true, description = "显示工作区状态（含未跟踪文件）")
public class StatusCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(StatusCommand.class);

    @Parameters(index = "0", arity = "0..1", description = "仓库根路径，默认为当前目录")
    private Path path;

    @Option(names = {"--"}, description = "以机器可读格式输出（每行一个文件，格式：?? <path>）")
    private boolean porcelain;

    private int exitCode = 0;

    @Override
    public void run() {
        exitCode = 0;
        Path start = path != null ? path.toAbsolutePath().normalize() : Paths.get("").toAbsolutePath().normalize();
        log.debug("status start path={}", start);

        Repository repo = Repository.find(start);
        if (repo == null) {
            log.debug("no repo found from {}", start);
            System.err.println("fatal: not a jit repository (or any of the parent directories): .git");
            exitCode = 1;
            return;
        }

        try {
            repo.getIndex().load();
            Set<String> trackedPaths = repo.getIndex().getEntries().stream()
                    .map(com.weixiao.repo.Index.Entry::getPath)
                    .collect(Collectors.toSet());

            Path root = repo.getRoot();
            List<String> untracked = collectUntracked(repo.getWorkspace(), root, root, "", trackedPaths);

            if (porcelain) {
                // 机器可读格式：每行一个文件，格式为 ?? <path>
                for (String p : untracked.stream().sorted().collect(Collectors.toList())) {
                    System.out.println("?? " + p);
                }
            } else {
                // 人类可读格式
                if (!untracked.isEmpty()) {
                    System.out.println("Untracked files:");
                    for (String p : untracked.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  " + p);
                    }
                } else {
                    System.out.println("nothing to commit, working tree clean");
                }
            }
        } catch (IOException e) {
            log.error("status failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    /**
     * 判断 index 中是否有该目录或其子路径被跟踪。
     * 若目录下没有任何文件出现在 index 中，则该目录整体可列为未跟踪，不展开内容。
     */
    private static boolean hasTrackedFilesUnder(String dirPath, Set<String> trackedPaths) {
        String prefix = dirPath.isEmpty() ? "" : dirPath + "/";
        return trackedPaths.stream()
                .anyMatch(p -> p.equals(dirPath) || p.startsWith(prefix));
    }

    /**
     * 判断目录下是否包含至少一个普通文件（递归）。Git 只关心内容，空目录不列为未跟踪。
     */
    private boolean hasAnyFileUnder(Workspace workspace, Path dir) throws IOException {
        for (Path child : workspace.listEntries(dir)) {
            if (Files.isRegularFile(child)) {
                return true;
            }
            if (Files.isDirectory(child) && hasAnyFileUnder(workspace, child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 递归收集工作区中未在 index 中跟踪的文件/目录路径（相对仓库根，/ 分隔）。
     * 若某目录下没有任何文件在 index 中，则只列出该目录本身（路径带尾部 /），不展开其内容；
     * 空目录不列出（Git 只关心内容，不跟踪空目录）。
     *
     * @param workspace    工作区，用于列出指定目录下的条目（{@link Workspace#listEntries(Path)}）
     * @param root         仓库根目录的绝对路径（工作区根），与 prefix 一起界定相对路径的基准
     * @param dir          当前正在遍历的目录的绝对路径，本次会对该目录调用 listEntries 并处理其子项
     * @param prefix       当前目录相对于仓库根的路径，使用 "/" 分隔；根目录时为空串 ""，递归时传入如 "a"、"a/b"
     * @param trackedPaths index 中已跟踪文件的路径集合（相对仓库根、"/" 分隔），用于判断文件或目录是否被跟踪
     * @return 本次递归收集到的未跟踪项列表，每项为相对仓库根的路径（文件无后缀，目录带 "/" 后缀）
     */
    private List<String> collectUntracked(Workspace workspace, Path root, Path dir, String prefix,
                                          Set<String> trackedPaths) throws IOException {
        List<String> result = new ArrayList<>();
        for (Path child : workspace.listEntries(dir)) {
            String name = child.getFileName().toString();
            String relativePath = prefix.isEmpty() ? name : prefix + "/" + name;

            if (Files.isRegularFile(child)) {
                if (!trackedPaths.contains(relativePath)) {
                    result.add(relativePath);
                }
            } else if (Files.isDirectory(child)) {
                if (!hasTrackedFilesUnder(relativePath, trackedPaths)) {
                    // 仅当目录内至少有一个文件时才列为未跟踪（空目录不列，Git 只关心内容）
                    if (hasAnyFileUnder(workspace, child)) {
                        result.add(relativePath + "/");
                    }
                } else {
                    result.addAll(collectUntracked(workspace, root, child, relativePath, trackedPaths));
                }
            }
        }
        return result;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
