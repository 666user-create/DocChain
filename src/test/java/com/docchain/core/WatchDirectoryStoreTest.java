package com.docchain.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WatchDirectoryStore — 监听目录列表持久化")
class WatchDirectoryStoreTest {

    @Test
    @DisplayName("保存后可原样读回（含顺序）")
    void shouldSaveAndLoadRoundTrip() throws Exception {
        Path file = Files.createTempDirectory("docchain-store-").resolve("dirs.txt");
        WatchDirectoryStore store = new WatchDirectoryStore(file);

        Path a = Path.of("D:/docs/2025");
        Path b = Path.of("D:/docs/合同");
        store.save(List.of(a, b));

        List<Path> loaded = store.load();
        assertEquals(2, loaded.size());
        assertEquals(a.toAbsolutePath().normalize(), loaded.get(0));
        assertEquals(b.toAbsolutePath().normalize(), loaded.get(1));
    }

    @Test
    @DisplayName("文件不存在时返回空列表，不抛异常")
    void loadMissingFileShouldReturnEmpty() {
        WatchDirectoryStore store = new WatchDirectoryStore(Path.of("no-such-dir/dirs.txt"));
        assertTrue(store.load().isEmpty());
    }

    @Test
    @DisplayName("保存是覆盖式：第二次保存反映最新列表")
    void saveShouldOverwritePreviousList() throws Exception {
        Path file = Files.createTempDirectory("docchain-store-").resolve("dirs.txt");
        WatchDirectoryStore store = new WatchDirectoryStore(file);

        store.save(List.of(Path.of("D:/a")));
        store.save(List.of(Path.of("D:/b")));

        List<Path> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals(Path.of("D:/b").toAbsolutePath().normalize(), loaded.get(0));
    }
}
