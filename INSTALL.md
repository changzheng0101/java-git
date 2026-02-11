# jit 安装和使用教程

本教程说明如何在任意文件夹下运行 `jit` 命令：将 jit 项目目录加入 PATH。

## 前置要求

- Java 17 或更高版本
- Maven 3.6+（用于构建项目）

## 安装步骤

### 步骤 1：构建项目

在 jit 项目根目录执行：

```bash
cd /path/to/java-git
mvn clean package
```

会在 `target/` 目录生成 `jit.jar`（包含所有依赖的可执行 jar）。

### 步骤 2：将 jit 目录加入 PATH

把**项目根目录**（即包含 `jit` 脚本的目录）加入 PATH，这样在任意目录都能直接执行 `jit`。

**macOS / Linux（当前终端有效）：**

```bash
export PATH="/path/to/java-git:$PATH"
```

**永久生效**：将上面一行写入 `~/.zshrc` 或 `~/.bashrc`，然后重新加载：

```bash
echo 'export PATH="/path/to/java-git:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

请将 `/path/to/java-git` 替换为你的实际项目路径（例如 `$HOME/IdeaProjects/learn-project/java-git`）。

### 步骤 3：验证安装

```bash
jit --help
```

若能看到 jit 的帮助信息，说明安装成功。

## 使用

在任意目录下可执行：

```bash
# 查看帮助
jit --help

# 初始化仓库
cd /path/to/your/project
jit init

# 子命令帮助
jit init --help
jit commit --help
```

## 常见问题

**Q: 提示「找不到 jit.jar 文件」**

确保已执行 `mvn package`，且项目根目录下存在 `target/jit.jar`。脚本会在「脚本所在目录」下查找 `target/jit.jar`。

**Q: 提示「command not found: jit」**

1. 确认 PATH 中包含 jit 项目根目录：`echo $PATH`
2. 确认项目根目录下有可执行脚本 `jit`：`ls -l /path/to/java-git/jit`
3. 若刚修改过 `~/.zshrc` / `~/.bashrc`，执行 `source ~/.zshrc` 或 `source ~/.bashrc`

**Q: 如何更新 jit？**

在项目目录执行 `mvn clean package` 即可。PATH 不变，脚本会自动使用新生成的 `target/jit.jar`。

**Q: 如何卸载？**

从 `~/.zshrc` 或 `~/.bashrc` 中删除添加的 `export PATH="..."` 那一行，然后 `source` 对应文件，或关闭并重新打开终端。
