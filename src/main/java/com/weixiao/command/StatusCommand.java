package com.weixiao.command;

import com.weixiao.model.StatusResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * jit status - 显示工作区状态，当前支持列出未跟踪文件（untracked files）。
 */
@Command(name = "status", mixinStandardHelpOptions = true, description = "显示工作区状态（含未跟踪文件）")
public class StatusCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(StatusCommand.class);

    @Option(names = {"--porcelain"}, description = "以机器可读格式输出（每行一个文件，格式：?? <path> 或  M <path>）")
    private boolean porcelain;

    @Override
    protected void initParams() {
        params = new LinkedHashMap<>();
        if (porcelain) {
            params.put("porcelain", "");
        }
    }

    @Override
    protected void doRun() {
        log.debug("status start path={}", getStartPath());
        try {
            repo.getIndex().load();
            StatusResult result = repo.getStatus();
            Set<String> workspaceModified = result.getWorkspaceModified();
            Set<String> workspaceDeleted = result.getWorkspaceDeleted();
            Set<String> workspaceUntracked = result.getWorkspaceUntracked();
            Set<String> indexAdded = result.getIndexAdded();
            Set<String> indexDeleted = result.getIndexDeleted();
            Set<String> indexModified = result.getIndexModified();

            if (isSet("porcelain")) {
                // 机器可读格式与 Git 一致：第一列为 index vs HEAD，第二列为 workspace vs index，无则用空格；untracked 为 "??"
                Set<String> indexPaths = new HashSet<>();
                indexPaths.addAll(indexAdded);
                indexPaths.addAll(indexModified);
                indexPaths.addAll(indexDeleted);
                indexPaths.addAll(workspaceModified);
                indexPaths.addAll(workspaceDeleted);
                List<String> sortedIndexPaths = indexPaths.stream().sorted().collect(Collectors.toList());
                for (String p : sortedIndexPaths) {
                    String line = formatPorcelainLine(p, indexAdded, indexModified, indexDeleted, workspaceModified, workspaceDeleted);
                    if (line != null) {
                        System.out.println(line);
                    }
                }
                for (String p : workspaceUntracked.stream().sorted().collect(Collectors.toList())) {
                    System.out.println("?? " + p);
                }
            } else {
                // 人类可读格式
                boolean hasChanges = false;
                // Changes to be committed (index vs HEAD)
                if (!indexAdded.isEmpty() || !indexModified.isEmpty() || !indexDeleted.isEmpty()) {
                    System.out.println("Changes to be committed:");
                    for (String p : indexAdded.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  new file:   " + p);
                    }
                    for (String p : indexModified.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  modified:   " + p);
                    }
                    for (String p : indexDeleted.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  deleted:    " + p);
                    }
                    hasChanges = true;
                }
                // Changes not staged for commit (workspace vs index)
                if (!workspaceModified.isEmpty() || !workspaceDeleted.isEmpty()) {
                    System.out.println("Changes not staged for commit:");
                    for (String p : workspaceModified.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  modified:   " + p);
                    }
                    for (String p : workspaceDeleted.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  deleted:    " + p);
                    }
                    hasChanges = true;
                }
                // Untracked files
                if (!workspaceUntracked.isEmpty()) {
                    System.out.println("Untracked files:");
                    for (String p : workspaceUntracked.stream().sorted().collect(Collectors.toList())) {
                        System.out.println("  " + p);
                    }
                    hasChanges = true;
                }
                if (!hasChanges) {
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
     * 将一条路径映射为 porcelain 格式的一行。
     * 第一列为 index vs HEAD（A/M/D/空格），第二列为 workspace vs index（M/D/空格）。
     * 若两列均为空格则返回 null（调用方不输出）。
     */
    private static String formatPorcelainLine(String path,
                                              Set<String> indexAdded, Set<String> indexModified, Set<String> indexDeleted,
                                              Set<String> workspaceModified, Set<String> workspaceDeleted) {
        char col1 = ' ';
        col1 = indexAdded.contains(path) ? 'A' : col1;
        col1 = indexModified.contains(path) ? 'M' : col1;
        col1 = indexDeleted.contains(path) ? 'D' : col1;

        char col2 = ' ';
        col2 = workspaceModified.contains(path) ? 'M' : col2;
        col2 = workspaceDeleted.contains(path) ? 'D' : col2;

        if (col1 == ' ' && col2 == ' ') return null;
        return "" + col1 + col2 + " " + path;
    }
}
