package com.docchain.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DocumentWatcher} 的防抖与过滤机制验证测试。
 *
 * <h3>测试策略</h3>
 * 由于防抖是异步的，测试使用 {@link CountDownLatch} 等待回调，
 * 并通过控制写入节奏来验证防抖窗口的聚合效果。
 */
@DisplayName("DocumentWatcher — 防抖与过滤测试")
class DocumentWatcherTest {

    private Path tempDir;
    private DocumentWatcher watcher;
    private Thread watcherThread;

    /** 线程安全地收集所有回调通知 */
    private final List<Path> notifiedFiles = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws IOException {
        notifiedFiles.clear();
        tempDir = Files.createTempDirectory("docwatcher-test-");
        watcher = new DocumentWatcher(500); // 500ms 防抖，加速测试
        watcher.addListener(notifiedFiles::add);
        watcher.register(tempDir, false); // 单层监听即可
        watcherThread = new Thread(watcher::start, "test-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        watcher.stop();
        watcherThread.join(2000);
        // 确保 watcher 线程完全退出，避免跨测试污染
        Thread.sleep(100);
        // 清理临时目录
        try {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        } catch (IOException ignored) {}
    }

    // ══════════════════════════════════════════════════════════════════
    // 1. 防抖机制测试
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("单次写入应在防抖窗口后触发通知")
    void singleWriteShouldNotifyAfterDebounce() throws Exception {
        Path file = tempDir.resolve("report.docx");

        Files.write(file, "v1".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(700); // 等待防抖触发

        assertTrue(notifiedFiles.contains(file),
                "单次写入后应收到通知, 实际通知: " + notifiedFiles);
    }

    @Test
    @DisplayName("2 秒内频繁写入同一文件，只触发 1 次通知")
    void rapidWritesToSameFileShouldTriggerOnlyOnce() throws Exception {
        Path file = tempDir.resolve("budget.xlsx");

        // 模拟 Office 保存行为：在 500ms 防抖窗口内连续写入 5 次
        for (int i = 0; i < 5; i++) {
            Files.write(file, ("iteration-" + i).getBytes(StandardCharsets.UTF_8));
            Thread.sleep(80); // 80ms 间隔, 远小于 500ms 防抖窗口
        }

        // 等待防抖完成
        Thread.sleep(800);

        long count = notifiedFiles.stream().filter(p -> p.equals(file)).count();
        assertEquals(1, count, "同一文件在防抖窗口内多次写入只应触发 1 次通知, 实际: " + count + " 通知: " + notifiedFiles);
    }

    @Test
    @DisplayName("不同文件的修改应独立防抖，各自触发通知")
    void differentFilesShouldNotifyIndependently() throws Exception {
        Path fileA = tempDir.resolve("a.docx");
        Path fileB = tempDir.resolve("b.docx");

        Files.write(fileA, "A".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(300);
        Files.write(fileB, "B".getBytes(StandardCharsets.UTF_8));

        Thread.sleep(700);

        assertTrue(notifiedFiles.contains(fileA), "应通知文件 A");
        assertTrue(notifiedFiles.contains(fileB), "应通知文件 B");
    }

    @Test
    @DisplayName("间歇写入（间隔 > 防抖窗口）应触发多次通知")
    void writesOutsideDebounceWindowShouldNotifyMultipleTimes() throws Exception {
        Path file = tempDir.resolve("notes.docx");

        // 第一次写入
        Files.write(file, "v1".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(700); // 等待第一次防抖触发
        assertEquals(1, countNotificationsFor(file), "第一次写入后应有 1 次通知");

        // 第二次写入（间隔超过 500ms 防抖窗口）
        Files.write(file, "v2".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(700); // 等待第二次防抖触发
        assertEquals(2, countNotificationsFor(file), "第二次写入后应有 2 次通知");
    }

    @Test
    @DisplayName("写入并立即删除文件，应取消防抖不触发通知")
    void writeThenDeleteShouldNotNotify() throws Exception {
        Path file = tempDir.resolve("ghost.docx");

        Files.write(file, "data".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(50);
        Files.delete(file);

        Thread.sleep(700);

        assertFalse(notifiedFiles.contains(file),
                "已删除的文件不应触发通知, 实际: " + notifiedFiles);
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. 临时文件过滤测试
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Office 锁定文件 ~$xxx.docx 不应触发通知")
    void officeLockFileShouldBeIgnored() throws Exception {
        Path lockFile = tempDir.resolve("~$important.docx");

        Files.write(lockFile, "lock".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(200);
        // 再模拟 Office 频繁改写锁定文件
        for (int i = 0; i < 5; i++) {
            Files.write(lockFile, ("lock-" + i).getBytes(StandardCharsets.UTF_8));
            Thread.sleep(50);
        }

        Thread.sleep(700);

        assertFalse(notifiedFiles.contains(lockFile),
                "Office 锁定文件 ~$xxx 不应出现在通知列表中, 实际: " + notifiedFiles);
    }

    @Test
    @DisplayName("*.tmp 临时文件不应触发通知")
    void tmpFileShouldBeIgnored() throws Exception {
        Path tmpFile = tempDir.resolve("autosave.tmp");

        Files.write(tmpFile, "temp".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(700);

        assertFalse(notifiedFiles.contains(tmpFile),
                ".tmp 文件不应触发通知, 实际: " + notifiedFiles);
    }

    @Test
    @DisplayName("*.swp / *.swx / *.lock / *.part 等临时文件应被过滤")
    void variousTempExtensionsShouldBeFiltered() throws Exception {
        Path[] temps = {
                tempDir.resolve("vim.swp"),
                tempDir.resolve("backup.swx"),
                tempDir.resolve("resource.lock"),
                tempDir.resolve("download.part"),
                tempDir.resolve("chrome.crdownload"),
        };

        for (Path t : temps) {
            Files.write(t, "x".getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(700);

        for (Path t : temps) {
            assertFalse(notifiedFiles.contains(t),
                    t.getFileName() + " 应被过滤, 但出现在通知中");
        }
    }

    @Test
    @DisplayName("系统元数据文件 .DS_Store / Thumbs.db 应被过滤")
    void systemMetadataFilesShouldBeIgnored() throws Exception {
        Path[] sysFiles = {
                tempDir.resolve(".DS_Store"),
                tempDir.resolve("Thumbs.db"),
        };

        for (Path f : sysFiles) {
            Files.write(f, "x".getBytes(StandardCharsets.UTF_8));
        }
        Thread.sleep(700);

        for (Path f : sysFiles) {
            assertFalse(notifiedFiles.contains(f),
                    f.getFileName() + " 应被过滤");
        }
    }

    @Test
    @DisplayName("以 ~ 开头的文件（非 ~$ 锁定文件）不被过滤")
    void tildePrefixedFileWithoutDollarShouldNotBeIgnored() throws Exception {
        Path tildeFile = tempDir.resolve("~tempfile");

        Files.write(tildeFile, "x".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(700);

        assertTrue(notifiedFiles.contains(tildeFile),
                "仅 ~ 前缀（无 $）的文件不应被过滤, 实际: " + notifiedFiles);
    }

    @Test
    @DisplayName("混合场景：正常文件 + 临时文件同时写入，只通知正常文件")
    void mixedNormalAndTempFiles() throws Exception {
        Path normal = tempDir.resolve("report.docx");
        Path lock = tempDir.resolve("~$report.docx");
        Path tmp = tempDir.resolve("backup.tmp");

        // 同时写入
        Files.write(normal, "real".getBytes(StandardCharsets.UTF_8));
        Files.write(lock, "lock".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp, "tmp".getBytes(StandardCharsets.UTF_8));

        // 模拟 Office 持续改动
        for (int i = 0; i < 3; i++) {
            Files.write(normal, ("real-" + i).getBytes(StandardCharsets.UTF_8));
            Files.write(lock, ("lock-" + i).getBytes(StandardCharsets.UTF_8));
            Thread.sleep(100);
        }

        Thread.sleep(700);

        assertTrue(notifiedFiles.contains(normal), "正常文件 report.docx 应被通知");
        assertFalse(notifiedFiles.contains(lock), "锁定文件 ~$report.docx 不应被通知");
        assertFalse(notifiedFiles.contains(tmp), "临时文件 backup.tmp 不应被通知");
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. 回调接口测试
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("多个监听器应同时收到通知")
    void multipleListenersShouldAllReceiveNotification() throws Exception {
        List<Path> listener1 = Collections.synchronizedList(new ArrayList<>());
        List<Path> listener2 = Collections.synchronizedList(new ArrayList<>());

        watcher.addListener(listener1::add);
        watcher.addListener(listener2::add);

        Path file = tempDir.resolve("multi-listener.docx");
        Files.write(file, "data".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(700);

        assertTrue(listener1.contains(file), "监听器 1 应收到通知");
        assertTrue(listener2.contains(file), "监听器 2 应收到通知");
    }

    @Test
    @DisplayName("移除监听器后不应再收到通知")
    void removedListenerShouldNotReceiveNotifications() throws Exception {
        List<Path> transientListener = Collections.synchronizedList(new ArrayList<>());
        DocumentWatcher.OnFileSavedListener ref = transientListener::add;
        watcher.addListener(ref);

        // 第一轮写入 — 应该收到
        Path file1 = tempDir.resolve("before-remove.docx");
        Files.write(file1, "data".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(700);
        assertTrue(transientListener.contains(file1));

        // 移除监听器
        watcher.removeListener(ref);
        transientListener.clear();

        // 第二轮写入 — 不应收到
        Path file2 = tempDir.resolve("after-remove.docx");
        Files.write(file2, "data".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(700);

        assertTrue(transientListener.isEmpty(),
                "移除后的监听器不应收到通知, 实际: " + transientListener);
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. 静态过滤方法测试
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isTemporaryOrSystemFile 对各种文件名正确判断")
    void staticFilterShouldCorrectlyIdentifyTempFiles() {
        assertTrue(DocumentWatcher.isTemporaryOrSystemFile(Path.of("~$test.docx")));
        assertTrue(DocumentWatcher.isTemporaryOrSystemFile(Path.of("data.tmp")));
        assertTrue(DocumentWatcher.isTemporaryOrSystemFile(Path.of("vim.swp")));
        assertTrue(DocumentWatcher.isTemporaryOrSystemFile(Path.of(".DS_Store")));
        assertTrue(DocumentWatcher.isTemporaryOrSystemFile(Path.of("Thumbs.db")));
        assertFalse(DocumentWatcher.isTemporaryOrSystemFile(Path.of("~backup")),
                "仅 ~ 前缀（非 ~$）不应被视为临时文件");

        assertFalse(DocumentWatcher.isTemporaryOrSystemFile(Path.of("report.docx")));
        assertFalse(DocumentWatcher.isTemporaryOrSystemFile(Path.of("budget.xlsx")));
        assertFalse(DocumentWatcher.isTemporaryOrSystemFile(Path.of("notes.txt")));
        assertFalse(DocumentWatcher.isTemporaryOrSystemFile(Path.of("image.png")));
        assertFalse(DocumentWatcher.isTemporaryOrSystemFile(Path.of("data.json")));
    }

    @Test
    @DisplayName("已存在的目录不被视为临时文件（即使名称匹配过滤规则）")
    void directoryShouldNotBeConsideredTemp() throws Exception {
        // 创建一个名称看似"临时"的目录，但实际目录不应被过滤
        Path dir = tempDir.resolve("~$tempdir");
        Files.createDirectory(dir);
        assertFalse(DocumentWatcher.isTemporaryOrSystemFile(dir),
                "真实存在的目录不应被视为临时文件");

        // 也验证不存在路径的行为：非目录 → 按文件名规则判断
        Path nonExistent = tempDir.resolve("~$ghost");
        assertTrue(DocumentWatcher.isTemporaryOrSystemFile(nonExistent),
                "不存在的路径按文件名规则判断，~$ 前缀应命中");
    }

    // ════════════════════════════════════════════════════════
    // 5. 注册/注销与版本库目录过滤
    // ════════════════════════════════════════════════════════

    @Test
    @DisplayName("递归监听时 .docchain-git 内部变更不应触发通知")
    void docchainGitDirShouldBeIgnored() throws Exception {
        Path gitObjects = tempDir.resolve(".docchain-git").resolve("objects");
        Files.createDirectories(gitObjects);

        watcher.register(tempDir, true); // 递归注册，.docchain-git 应被跳过

        Files.write(gitObjects.resolve("pack-abc"), "obj".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(300);

        Path real = tempDir.resolve("real-doc.docx");
        Files.write(real, "v1".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(900);

        assertTrue(notifiedFiles.stream().noneMatch(p -> p.toString().contains(".docchain-git")),
                ".docchain-git 内部变更不应被通知, 实际: " + notifiedFiles);
        assertTrue(notifiedFiles.contains(real), "普通文件仍应正常通知");
    }

    @Test
    @DisplayName("unregister 后该目录不再触发通知")
    void unregisterShouldStopNotifications() throws Exception {
        watcher.unregister(tempDir);

        Files.write(tempDir.resolve("after-unregister.docx"), "x".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(800);

        assertTrue(notifiedFiles.isEmpty(),
                "注销后不应再收到通知, 实际: " + notifiedFiles);
    }

    // ── 辅助方法 ─────────────────────────────────────────────────

    private long countNotificationsFor(Path file) {
        return notifiedFiles.stream().filter(p -> p.equals(file)).count();
    }
}
