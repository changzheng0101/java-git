package com.weixiao.command;

import com.weixiao.model.StatusResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * jit status - 显示工作区状态，当前支持列出未跟踪文件（untracked files）。
 */
@Command(name = "status", mixinStandardHelpOptions = true, description = "显示工作区状态（含未跟踪文件）")
public class StatusCommand extends BaseCommand {

    private static final Logger log = LoggerFactory.getLogger(StatusCommand.class);
    private static final String CONFLICT_UU = "1,2,3";
    private static final String CONFLICT_UD = "1,2";
    private static final String CONFLICT_DU = "1,3";
    private static final String CONFLICT_AA = "2,3";
    private static final String CONFLICT_AU = "2";
    private static final String CONFLICT_UA = "3";
    private static final Map<String, String> CONFLICT_SHORT_STATUS = new java.util.LinkedHashMap<>();
    private static final Map<String, String> CONFLICT_LONG_STATUS = new java.util.LinkedHashMap<>();

    static {
        CONFLICT_SHORT_STATUS.put(CONFLICT_UU, "UU");
        CONFLICT_SHORT_STATUS.put(CONFLICT_UD, "UD");
        CONFLICT_SHORT_STATUS.put(CONFLICT_DU, "DU");
        CONFLICT_SHORT_STATUS.put(CONFLICT_AA, "AA");
        CONFLICT_SHORT_STATUS.put(CONFLICT_AU, "AU");
        CONFLICT_SHORT_STATUS.put(CONFLICT_UA, "UA");

        CONFLICT_LONG_STATUS.put(CONFLICT_UU, "both modified");
        CONFLICT_LONG_STATUS.put(CONFLICT_UD, "deleted by them");
        CONFLICT_LONG_STATUS.put(CONFLICT_DU, "deleted by us");
        CONFLICT_LONG_STATUS.put(CONFLICT_AA, "both added");
        CONFLICT_LONG_STATUS.put(CONFLICT_AU, "added by us");
        CONFLICT_LONG_STATUS.put(CONFLICT_UA, "added by them");
    }

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
            java.util.Map<String, Set<Integer>> conflicts = result.getConflicts();

            if (isSet("porcelain")) {
                // 机器可读格式与 Git 一致：第一列为 index vs HEAD，第二列为 workspace vs index，无则用空格；untracked 为 "??"
                Set<String> allPaths = new HashSet<>();
                allPaths.addAll(conflicts.keySet());
                allPaths.addAll(indexAdded);
                allPaths.addAll(indexModified);
                allPaths.addAll(indexDeleted);
                allPaths.addAll(workspaceModified);
                allPaths.addAll(workspaceDeleted);
                allPaths.addAll(workspaceUntracked);
                List<String> sortedPaths = allPaths.stream().sorted().collect(Collectors.toList());
                for (String p : sortedPaths) {
                    String line = formatPorcelainLine(
                            p,
                            conflicts,
                            workspaceUntracked,
                            indexAdded,
                            indexModified,
                            indexDeleted,
                            workspaceModified,
                            workspaceDeleted
                    );
                    if (line != null) {
                        System.out.println(line);
                    }
                }
            } else {
                // 人类可读格式
                boolean isClean = conflicts.isEmpty()
                        && indexAdded.isEmpty()
                        && indexModified.isEmpty()
                        && indexDeleted.isEmpty()
                        && workspaceModified.isEmpty()
                        && workspaceDeleted.isEmpty()
                        && workspaceUntracked.isEmpty();
                if (isClean) {
                    System.out.println("nothing to commit, working tree clean");
                    return;
                }
                // Changes to be committed (index vs HEAD)
                printSection(
                        "Changes to be committed:",
                        linesWithPrefix("  new file:   ", indexAdded),
                        linesWithPrefix("  modified:   ", indexModified),
                        linesWithPrefix("  deleted:    ", indexDeleted)
                );
                // Unmerged paths
                printSection("Unmerged paths:", conflictLongLines(conflicts));
                // Changes not staged for commit (workspace vs index)
                printSection(
                        "Changes not staged for commit:",
                        linesWithPrefix("  modified:   ", workspaceModified),
                        linesWithPrefix("  deleted:    ", workspaceDeleted)
                );
                // Untracked files
                printSection("Untracked files:", linesWithPrefix("  ", workspaceUntracked));
            }
        } catch (IOException e) {
            log.error("status failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    /**
     * 将一条路径映射为 porcelain 格式的一行。
     * 冲突路径输出 UU，未跟踪路径输出 ??，其余按两列格式输出。
     * 第一列为 index vs HEAD（A/M/D/空格），第二列为 workspace vs index（M/D/空格）。
     * 若两列均为空格则返回 null（调用方不输出）。
     */
    private static String formatPorcelainLine(String path,
                                              java.util.Map<String, Set<Integer>> conflicts,
                                              Set<String> workspaceUntracked,
                                              Set<String> indexAdded, Set<String> indexModified, Set<String> indexDeleted,
                                              Set<String> workspaceModified, Set<String> workspaceDeleted) {
        if (conflicts.containsKey(path)) {
            return CONFLICT_SHORT_STATUS.getOrDefault(conflictStageKey(conflicts.get(path)), "UU") + " " + path;
        }

        char col1 = ' ';
        col1 = indexAdded.contains(path) ? 'A' : col1;
        col1 = indexModified.contains(path) ? 'M' : col1;
        col1 = indexDeleted.contains(path) ? 'D' : col1;

        char col2 = ' ';
        col2 = workspaceModified.contains(path) ? 'M' : col2;
        col2 = workspaceDeleted.contains(path) ? 'D' : col2;

        if (col1 != ' ' || col2 != ' ') {
            return "" + col1 + col2 + " " + path;
        }
        if (workspaceUntracked.contains(path)) {
            return "?? " + path;
        }
        return null;
    }

    @SafeVarargs
    private static void printSection(String title, List<String>... groups) {
        List<String> lines = new ArrayList<>();
        for (List<String> group : groups) {
            lines.addAll(group);
        }
        if (lines.isEmpty()) {
            return;
        }
        System.out.println(title);
        for (String line : lines) {
            System.out.println(line);
        }
    }

    private static List<String> linesWithPrefix(String prefix, Set<String> paths) {
        return paths.stream()
                .sorted()
                .map(p -> prefix + p)
                .collect(Collectors.toList());
    }

    private static List<String> conflictLongLines(java.util.Map<String, Set<Integer>> conflicts) {
        return conflicts.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> "  " + CONFLICT_LONG_STATUS.getOrDefault(conflictStageKey(e.getValue()), "both modified") + ":   " + e.getKey())
                .collect(Collectors.toList());
    }

    private static String conflictStageKey(Set<Integer> stages) {
        return stages.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }
}
