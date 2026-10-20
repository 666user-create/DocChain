package com.docchain.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 监听目录列表的持久化存储。
 *
 * <p>格式：UTF-8 纯文本，一行一个绝对路径。刻意不引入数据库依赖——
 * 这个量级的数据（几十行路径）用文本文件更简单、更透明，出问题时用户自己也能看懂。</p>
 *
 * <p>写入采用「临时文件 + 原子替换」，避免程序被强杀时写坏列表。</p>
 */
public class WatchDirectoryStore {

    private static final Logger log = LoggerFactory.getLogger(WatchDirectoryStore.class);

    private final Path file;

    public WatchDirectoryStore(Path file) {
        this.file = file;
    }

    /**
     * 读取已保存的监听目录列表。
     *
     * <p>返回顺序与保存顺序一致。目录当前不存在（例如移动硬盘未插）时
     * 仍然保留在列表中，由调用方决定如何展示，避免"插回硬盘后监听悄悄丢失"。</p>
     */
    public List<Path> load() {
        List<Path> dirs = new ArrayList<>();
        if (!Files.isRegularFile(file)) return dirs;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                dirs.add(Path.of(trimmed).toAbsolutePath().normalize());
            }
        } catch (Exception e) {
            log.warn("Failed to load watch directory list from {}: {}", file, e.getMessage());
        }
        return dirs;
    }

    /**
     * 覆盖保存监听目录列表。
     */
    public void save(Collection<Path> dirs) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>();
            for (Path d : dirs) {
                lines.add(d.toAbsolutePath().normalize().toString());
            }
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Saved {} watch directories to {}", lines.size(), file);
        } catch (IOException e) {
            log.error("Failed to save watch directory list to {}", file, e);
        }
    }
}
