# jit

用 Java 实现 Git 的版本控制功能，命令行入口为 `jit`，对应并替代日常使用的 `git` 命令。随功能推进会逐步支持常用子命令（如 commit、status、log 等）。

## 项目想法

- **目标**：在 JVM 上实现一套可用的版本控制 CLI，行为与 Git 类似，便于学习或二次扩展。
- **命名**：`jit` 作为主命令名，与 `git` 对应；后续通过子命令（如 `jit commit`）扩展能力。

## 已实现内容

- **根命令** `jit`：入口与帮助（`jit -h` / `jit --help`）
- **`jit init`**：初始化空的 jit 仓库，创建 `.git`、`.git/objects`、`.git/refs/heads` 目录结构
- **`jit commit`**：子命令框架已接入，目前仅能识别为 commit 命令，尚未实现具体提交逻辑

后续实现更多功能时会同步更新本文档。

## 快速开始

### 安装（在任意目录使用 jit 命令）

将 jit 项目目录加入 PATH 即可，详见 [INSTALL.md](INSTALL.md)。

```bash
# 1. 构建项目
mvn clean package

# 2. 将 jit 项目目录加入 PATH（请将 /path/to/java-git 改为实际路径）
export PATH="/path/to/java-git:$PATH"
# 永久生效：echo 'export PATH="/path/to/java-git:$PATH"' >> ~/.zshrc && source ~/.zshrc
```

### 使用

```bash
jit --help
jit init
jit init --help
```

### 开发时在项目目录运行

```bash
mvn exec:java -Dexec.args="--help"
mvn exec:java -Dexec.args="init"
# 或先构建后：./jit --help  ./jit init
```

## 技术栈

- Java 17+
- Maven
- [Picocli](https://picocli.info/)（命令行解析）
- JUnit 5 + AssertJ（测试框架）

## 文档

- [安装和使用教程](INSTALL.md) - 将 jit 目录加入 PATH 后在任意目录使用 jit
