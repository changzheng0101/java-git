# jit

<p align="center">
  <strong>A Git-compatible version-control playground built in Java.</strong>
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

`jit` is a from-scratch Java implementation of core Git concepts. It is designed as a readable, test-driven learning project that mirrors the behavior of everyday Git commands while keeping the internals approachable: objects, refs, the index, tree diffs, revision parsing, checkout migration, and merge conflict handling are all implemented in plain Java.

> The project is inspired by the structure of polished, high-star open-source READMEs: clear positioning, fast onboarding, feature matrices, architecture notes, and an honest roadmap.

## Why jit?

- **Learn Git from the inside out** — understand how blobs, trees, commits, refs, and the index work together.
- **Stay close to real Git formats** — loose objects use `type size\0body` with zlib compression; the index follows the `DIRC` binary format.
- **Keep the codebase hackable** — Maven, Java 17, picocli, JUnit 5, AssertJ, and small domain modules.
- **Build with confidence** — the current codebase includes command-level and domain-level tests for the implemented behavior.

## Feature Matrix

| Area | Status | Notes |
| --- | --- | --- |
| Repository lifecycle | ✅ Implemented | `jit init`, repository discovery via parent directories |
| Object database | ✅ Implemented | Loose `blob`, `tree`, and `commit` objects |
| Index | ✅ Implemented | Binary read/write, stage entries, conflict entries |
| Working tree | ✅ Implemented | File scanning, stat metadata, mode handling |
| Basic workflow | ✅ Implemented | `add`, `commit`, `status`, `diff`, `log` |
| Branching | ✅ Implemented | `branch`, branch listing, creation, deletion, verbose output |
| Checkout | ✅ Implemented | Branch checkout and detached HEAD checkout |
| Revision parsing | ✅ Implemented | `HEAD`, `@`, branch names, short OIDs, `^`, `~n`, `A..B` |
| Merge | ✅ Implemented | Fast-forward, common ancestor lookup, three-way content merge, conflict stages |
| Remote config | ✅ Partial | `remote`, `remote -v`, `remote remove/rm` |
| Network remotes | 🚧 Planned | `fetch`, `clone`, `push`, pack protocol |
| Packfiles | 🚧 Planned | Pack parsing/writing and object transfer |

## Quick Start

### Requirements

- Java 17 or later
- Maven 3.6+

### Build

```bash
mvn clean package
```

The shaded executable jar is generated at:

```bash
target/jit.jar
```

### Use the CLI

The repository includes a lightweight `jit` wrapper script:

```bash
./jit --help
```

To use `jit` from any directory, add the project root to your `PATH`:

```bash
export PATH="/path/to/java-git:$PATH"
```

See [INSTALL.md](INSTALL.md) for a detailed installation guide.

## Example Workflow

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

## Command Overview

```text
jit init                 Create a repository
jit add <path...>        Add files or directories to the index
jit commit -m <msg>      Create a commit from the index
jit status               Show working tree and index status
jit diff [--cached]      Show working tree or staged differences
jit log [REVISION]       Show commit history
jit branch [NAME]        List, create, or delete branches
jit checkout <REF>       Switch branches or detach HEAD
jit merge <REV>          Merge another revision into HEAD
jit remote [-v]          List or remove configured remotes
```

## Architecture

```text
src/main/java/com/weixiao
├── command/     # picocli command adapters
├── repo/        # repository, object database, refs, index, status, migration
├── obj/         # Git object model: blob, tree, commit
├── diff/        # tree diff and diff entries
├── merge/       # common ancestors, diff3, merge resolution
├── revision/    # revision expression parser
├── config/      # Git-style config parser and writer
├── model/       # small DTOs and status models
└── utils/       # path, binary IO, hashing, color, diff helpers
```

At a high level, a command follows this flow:

```text
CLI command
  -> repository discovery
  -> load refs / index / objects
  -> perform domain operation
  -> update objects / index / refs / working tree
  -> print Git-like output
```

## Development

Run the full test suite:

```bash
mvn test
```

Run one test class:

```bash
mvn -Dtest=IndexTest test
```

Enable debug logs:

```bash
JIT_DEBUG=true ./jit status
java -Djit.debug=true -jar target/jit.jar status
java -Djit.log.level=DEBUG -jar target/jit.jar status
```

## Roadmap

- Harden working-tree safety for checkout and merge operations.
- Extract command output from core domain logic for cleaner testing and reuse.
- Replace global repository state with explicit repository instances.
- Add `rm`, `reset`, `restore`, `tag`, `show`, and richer `config` commands.
- Improve merge and revision traversal for complex multi-parent histories.
- Implement remote object negotiation, fetch, clone, push, and packfiles.

## Project Status

`jit` is an educational implementation, not a drop-in replacement for production Git. It intentionally focuses on clarity and incremental correctness. The best way to contribute is to pick a Git behavior, encode it as a failing test, and then evolve the implementation until the behavior matches.

## Contributing

Contributions are welcome. Please keep changes focused, add or update tests for behavior changes, and prefer small, reviewable pull requests. See [CONTRIBUTION.md](CONTRIBUTION.md) for project-specific notes.
