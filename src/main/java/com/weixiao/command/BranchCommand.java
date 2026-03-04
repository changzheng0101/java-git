package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.obj.Commit;
import com.weixiao.obj.GitObject;
import com.weixiao.repo.Refs;
import com.weixiao.repo.Repository;
import com.weixiao.repo.SysRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * jit branch：
 * - 无参数：列出所有分支，按字母排序，当前分支前加 *；
 * - 有 NAME 参数：在当前 HEAD 上新建 refs/heads/&lt;name&gt;（分支名校验与 Git check-ref-format 一致）。
 */
@Command(name = "branch", mixinStandardHelpOptions = true, description = "列出或创建分支")
public class BranchCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(BranchCommand.class);

    @ParentCommand
    private Jit jit;

    @Option(names = {"-v", "--verbose"}, description = "显示每个分支指向的提交和标题行")
    private boolean verbose;

    @Parameters(index = "0", arity = "0..1", paramLabel = "NAME", description = "新分支名称（省略则列出分支）")
    private String branchName;

    private int exitCode = 0;

    @Override
    public void run() {
        exitCode = 0;
        Path start = jit.getStartPath();
        log.debug("branch start path={} name={}", start, branchName);

        Repository repo = Repository.find(start);
        if (repo == null) {
            log.debug("no repo found from {}", start);
            System.err.println("fatal: not a jit repository (or any of the parent directories): .git");
            exitCode = 1;
            return;
        }

        try {
            if (branchName == null || branchName.isEmpty()) {
                listBranches(repo);
                return;
            }

            String invalid = Refs.validateBranchName(branchName);
            if (invalid != null) {
                System.err.println("fatal: '" + branchName + "' is not a valid branch name: " + invalid);
                exitCode = 1;
                return;
            }
            if (repo.getRefs().branchExists(branchName)) {
                System.err.println("fatal: A branch named '" + branchName + "' already exists.");
                exitCode = 1;
                return;
            }
            String oid = repo.getRefs().readHead();
            if (oid == null || oid.isEmpty()) {
                System.err.println("fatal: Not a valid object name: 'HEAD'.");
                exitCode = 1;
                return;
            }
            repo.getRefs().createBranch(branchName, oid);
            log.info("branch created name={} oid={}", branchName, oid);
        } catch (IOException e) {
            log.error("branch failed", e);
            System.err.println("fatal: " + e.getMessage());
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /**
     * 列出所有分支，按名字排序；当前 HEAD 所在分支前加 *。
     * 若 --verbose，则附加输出 abbrev commitId 和 message 首行。
     */
    private void listBranches(Repository repo) throws IOException {
        Path gitDir = repo.getGitDir();
        Path headsDir = gitDir.resolve("refs").resolve("heads");
        if (!Files.exists(headsDir)) {
            // 未创建任何分支时与 Git 一致：无输出、退出码为 0
            return;
        }

        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(headsDir)) {
            names = stream
                    .filter(Files::isRegularFile)
                    .map(p -> headsDir.relativize(p).toString().replace(java.io.File.separatorChar, '/'))
                    .collect(Collectors.toList());
        }
        Collections.sort(names);

        String headRef = repo.getRefs().getHeadRef();

        for (String name : names) {
            String fullRefPath = Refs.REFS_HEADS + name;
            boolean current = headRef != null && headRef.equals(fullRefPath);
            String prefix = current ? "*" : " ";

            if (!verbose) {
                System.out.println(prefix + " " + name);
            } else {
                SysRef ref = new SysRef(fullRefPath);
                String oid = repo.getRefs().readRef(ref);
                if (oid == null || oid.isEmpty()) {
                    System.out.println(prefix + " " + name);
                    continue;
                }
                String abbrev = oid.length() >= 7 ? oid.substring(0, 7) : oid;
                String subject = getCommitShortMessage(repo, oid);
                // 与 git 类似：分支名、缩写 oid、标题行
                System.out.println(prefix + " " + name + " " + abbrev + " " + subject);
            }
        }
    }

    private static String getCommitShortMessage(Repository repo, String commitOid) throws IOException {
        GitObject obj = repo.getDatabase().load(commitOid);
        if (!"commit".equals(obj.getType())) {
            return "";
        }
        Commit commit = Commit.fromBytes(obj.toBytes());
        String msg = commit.getMessage();
        int nl = msg.indexOf('\n');
        return nl >= 0 ? msg.substring(0, nl).trim() : msg.trim();
    }
}
