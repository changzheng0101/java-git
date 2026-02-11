package com.weixiao.command;

import picocli.CommandLine;
import picocli.CommandLine.*;

/**
 * jit commit 子命令，暂仅做命令识别，不实现具体逻辑。
 */
@Command(name = "commit", mixinStandardHelpOptions = true, description = "提交变更到仓库")
public class CommitCommand implements Runnable {

    @Override
    public void run() {
        // 仅识别为 commit 命令，具体功能后续实现
    }
}
