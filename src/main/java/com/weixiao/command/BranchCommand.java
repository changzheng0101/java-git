package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.repo.Refs;
import com.weixiao.repo.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Path;

/**
 * jit branch - 创建分支：在当前 HEAD 上新建 refs/heads/&lt;name&gt;。
 * 分支名校验与 Git check-ref-format 一致；分支已存在或当前无 commit 时失败。
 */
@Command(name = "branch", mixinStandardHelpOptions = true, description = "创建分支（在当前 HEAD 上新建 refs/heads/<name>）")
public class BranchCommand implements Runnable, IExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(BranchCommand.class);

    @ParentCommand
    private Jit jit;

    @Parameters(index = "0", arity = "1", paramLabel = "NAME", description = "新分支名称")
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
}
