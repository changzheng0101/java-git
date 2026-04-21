# jit

<p align="center">
  <strong>一个用 Java 从零实现的 Git 兼容版本控制实验场。</strong>
</p>

<p align="center">
  <a href="README.zh-CN.md">简体中文</a> · <a href="README.md">English</a>
</p>

<p align="center">
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Maven" src="https://img.shields.io/badge/build-Maven-blue">
  <img alt="JUnit 5" src="https://img.shields.io/badge/tests-JUnit%205-brightgreen">
  <img alt="CLI" src="https://img.shields.io/badge/interface-CLI-black">
  <img alt="License" src="https://img.shields.io/badge/license-TBD-lightgrey">
</p>

`jit` 是一个用 Java 从零实现 Git 核心机制的命令行项目。它不是简单调用系统 `git`，而是用可读、可测试、可扩展的 Java 代码实现对象库、引用、暂存区、树差异、修订解析、checkout 迁移和 merge 冲突处理。

> 文档结构参考了高星开源项目常见的 README 风格：清晰定位、快速上手、功能矩阵、架构说明、开发指南和真实路线图。

## 为什么做 jit？

- **从内部理解 Git**：真正理解 `blob`、`tree`、`commit`、`ref`、`index` 如何协作。
- **尽量贴近真实格式**：对象采用 `type size\0body` + zlib 压缩，index 采用 `DIRC` 二进制格式。
- **保持项目可读可改**：Java 17、Maven、picocli、JUnit 5、AssertJ，小模块组织。
- **用测试驱动演进**：当前已覆盖命令层和核心领域逻辑，方便继续安全扩展。

## 功能矩阵

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| 仓库生命周期 | ✅ 已实现 | `jit init`，从当前目录向父目录查找仓库 |
| 对象数据库 | ✅ 已实现 | loose object：`blob`、`tree`、`commit` |
| 暂存区 Index | ✅ 已实现 | 二进制读写、stage 条目、冲突条目 |
| 工作区 | ✅ 已实现 | 文件扫描、stat 元数据、mode 处理 |
| 基础流程 | ✅ 已实现 | `add`、`commit`、`status`、`diff`、`log` |
| 分支 | ✅ 已实现 | `branch`，分支列表、创建、删除、详细显示 |
| Checkout | ✅ 已实现 | 分支切换和 detached HEAD |
| Revision 解析 | ✅ 已实现 | `HEAD`、`@`、分支名、短 OID、`^`、`~n`、`A..B` |
| Merge | ✅ 已实现 | fast-forward、共同祖先、三方文本合并、冲突 stage |
| Remote 配置 | ✅ 部分实现 | `remote`、`remote -v`、`remote remove/rm` |
| 网络远程 | 🚧 计划中 | `fetch`、`clone`、`push`、pack 协议 |
| Packfile | 🚧 计划中 | pack 解析、写入和对象传输 |

## 快速开始

### 环境要求

- Java 17 或更高版本
- Maven 3.6+

### 构建

```bash
mvn clean package
```

构建完成后会生成可执行 fat jar：

```bash
target/jit.jar
```

### 使用命令行

项目根目录提供了一个轻量级 `jit` 包装脚本：

```bash
./jit --help
```

如果希望在任意目录执行 `jit`，把项目根目录加入 `PATH`：

```bash
export PATH="/path/to/java-git:$PATH"
```

更详细的安装步骤见 [INSTALL.md](INSTALL.md)。

## 示例流程

```bash
mkdir demo && cd demo
jit init

echo "hello jit" > hello.txt
jit add hello.txt
jit commit -m "Initial commit"

jit status
jit log --oneline

jit branch feature
jit checkout feature
echo "ship it" >> hello.txt
jit add hello.txt
jit commit -m "Update hello"

jit checkout master
jit merge feature
```

## 命令概览

```text
jit init                 创建仓库
jit add <path...>        将文件或目录加入暂存区
jit commit -m <msg>      基于暂存区创建提交
jit status               查看工作区和暂存区状态
jit diff [--cached]      查看工作区差异或已暂存差异
jit log [REVISION]       查看提交历史
jit branch [NAME]        列出、创建或删除分支
jit checkout <REF>       切换分支或进入 detached HEAD
jit merge <REV>          将另一个修订合并到 HEAD
jit remote [-v]          列出或删除远程配置
```

## 架构设计

```text
src/main/java/com/weixiao
├── command/     # picocli 命令适配层
├── repo/        # 仓库、对象数据库、引用、暂存区、状态、迁移
├── obj/         # Git 对象模型：blob、tree、commit
├── diff/        # tree diff 和 diff entry
├── merge/       # 共同祖先、diff3、merge resolve
├── revision/    # revision 表达式解析
├── config/      # Git 风格配置解析与写回
├── model/       # 状态模型和 DTO
└── utils/       # 路径、二进制 IO、哈希、颜色、diff 工具
```

一次命令执行大致会经过：

```text
CLI command
  -> 查找仓库
  -> 加载 refs / index / objects
  -> 执行领域操作
  -> 更新 objects / index / refs / working tree
  -> 输出类似 Git 的结果
```

## 开发指南

运行完整测试：

```bash
mvn test
```

运行单个测试类：

```bash
mvn -Dtest=IndexTest test
```

开启调试日志：

```bash
JIT_DEBUG=true ./jit status
java -Djit.debug=true -jar target/jit.jar status
java -Djit.log.level=DEBUG -jar target/jit.jar status
```

## 路线图

- 强化 checkout 和 merge 的工作区安全校验，避免覆盖未提交内容。
- 将命令输出与核心领域逻辑拆分，提升测试性和复用性。
- 移除全局仓库状态，改为显式传递 `Repository` 实例。
- 增加 `rm`、`reset`、`restore`、`tag`、`show` 和更完整的 `config` 命令。
- 改进复杂多父提交场景下的 merge 和 revision 遍历。
- 实现远程对象协商、fetch、clone、push 和 packfile。

## 项目状态

`jit` 是一个学习型实现，不是生产环境中 `git` 的完整替代品。项目更关注清晰、可解释、可迭代的实现方式。推荐的贡献方式是：先为一个 Git 行为写出失败测试，再让实现逐步靠近真实 Git 行为。

## 贡献

欢迎贡献。请尽量保持改动聚焦，为行为变化补充或更新测试，并优先提交小而清晰的变更。项目相关说明见 [CONTRIBUTION.md](CONTRIBUTION.md)。
