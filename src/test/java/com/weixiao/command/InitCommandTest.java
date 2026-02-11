package com.weixiao.command;

import com.weixiao.Jit;
import com.weixiao.JitTestUtil;
import com.weixiao.JitTestUtil.ExecuteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jit init 命令测试。通过 Jit 主命令执行子命令，使用 @TempDir 保证每次运行在独立目录，可重复执行。
 */
@DisplayName("InitCommand 测试")
class InitCommandTest {

    /**
     * 使用 Jit.createCommandLine() 作为唯一入口点，确保与主程序使用相同的配置。
     */
    private static final CommandLine JIT = Jit.createCommandLine();

    @Test
    @DisplayName("通过 jit init 在指定路径执行会创建 .git、.git/objects、.git/refs/heads")
    void init_createsGitStructure(@TempDir Path tempDir) throws Exception {
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "init", tempDir.toString());
        assertThat(result.exitCode).isEqualTo(0);
        assertThat(result.output).contains("Initialized empty Jit repository");
        assertThat(result.output).contains("Current branch: master");

        Path gitDir = tempDir.resolve(".git");
        assertThat(gitDir).exists();
        assertThat(Files.isDirectory(gitDir)).isTrue();

        assertThat(tempDir.resolve(".git").resolve("objects")).exists();
        assertThat(Files.isDirectory(tempDir.resolve(".git").resolve("objects"))).isTrue();

        assertThat(tempDir.resolve(".git").resolve("refs").resolve("heads")).exists();
        assertThat(Files.isDirectory(tempDir.resolve(".git").resolve("refs").resolve("heads"))).isTrue();

        Path headFile = tempDir.resolve(".git").resolve("HEAD");
        assertThat(headFile).exists();
        String headContent = new String(Files.readAllBytes(headFile), StandardCharsets.UTF_8);
        assertThat(headContent).isEqualTo("ref: refs/heads/master");
    }

    @Test
    @DisplayName("通过 jit init 在同一目录重复执行仍成功，可重复运行")
    void init_idempotent(@TempDir Path tempDir) {
        int first = JIT.execute("init", tempDir.toString());
        int second = JIT.execute("init", tempDir.toString());

        assertThat(first).isEqualTo(0);
        assertThat(second).isEqualTo(0);
        assertThat(tempDir.resolve(".git")).exists();
        assertThat(tempDir.resolve(".git").resolve("objects")).exists();
        assertThat(tempDir.resolve(".git").resolve("refs").resolve("heads")).exists();
    }

    @Test
    @DisplayName("通过 jit init 执行时输出包含仓库路径")
    void init_outputContainsRepoPath(@TempDir Path tempDir) {
        ExecuteResult result = JitTestUtil.executeWithCapturedOut(JIT, "init", tempDir.toString());
        assertThat(result.output).contains(".git");
        assertThat(result.output).contains("Initialized empty Jit repository");
        assertThat(result.output).contains("Current branch: master");
    }
}
