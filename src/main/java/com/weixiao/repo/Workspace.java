package com.weixiao.repo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 工作区：列出并读取工作目录中的文件（排除 .、..、.git）。
 */
public final class Workspace {

    private static final Logger log = LoggerFactory.getLogger(Workspace.class);

    private static final String GIT_DIR = ".git";
    private final Path root;

    /** 以给定路径为工作区根目录。 */
    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 列出根目录下的普通文件（不含目录、不含 .git）。
     * 简化实现：仅一层，不递归子目录。
     */
    public List<String> listFiles() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (GIT_DIR.equals(name)) continue;
                names.add(name);
            }
        }
        log.debug("listFiles root={} count={} names={}", root, names.size(), names);
        return names;
    }

    /** 读取工作区根目录下名为 name 的文件的全部字节。 */
    public byte[] readFile(String name) throws IOException {
        return Files.readAllBytes(root.resolve(name));
    }

    /** 工作区根目录路径。 */
    public Path getRoot() {
        return root;
    }
}
