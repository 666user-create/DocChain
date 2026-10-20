package com.docchain.core;

import com.docchain.model.VersionCommit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GitRepositoryManager — JGit 增量存储测试")
class GitRepositoryManagerTest {

    private Path tempDir;
    private GitRepositoryManager manager;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("docchain-git-test-");
        manager = GitRepositoryManager.init(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.close();
        try {
            try (var s = Files.walk(tempDir)) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        } catch (IOException ignored) {}
    }

    // ══════════════════════════════════════════════════════════════════
    // 1. 仓库初始化
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("初始化创建 .docchain-git 而非 .git")
    void shouldInitDocchainGitDirectory() {
        Path gitDir = tempDir.resolve(".docchain-git");
        Path standardGit = tempDir.resolve(".git");

        assertTrue(Files.isDirectory(gitDir), ".docchain-git 应存在");
        assertFalse(Files.isDirectory(standardGit), "不应创建标准 .git 目录");
    }

    @Test
    @DisplayName("初始化后仓库包含根提交（marker commit）")
    void shouldHaveInitialMarkerCommit() throws Exception {
        List<VersionCommit> history = manager.getRevisionHistory(".docchain");
        assertFalse(history.isEmpty(), "应存在 marker 文件的初始提交");
    }

    @Test
    @DisplayName("重复 init 不会报错，直接打开已有仓库")
    void shouldReopenExistingRepo() throws Exception {
        // 在已有仓库上再次 init
        GitRepositoryManager m2 = GitRepositoryManager.init(tempDir);
        assertNotNull(m2);
        m2.close();
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. autoCommit — 有变更时产生提交
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("有内容变更时 autoCommit 返回 commit SHA-1")
    void autoCommitWithChangesShouldProduceCommit() throws Exception {
        Path file = tempDir.resolve("proposal.docx");
        Files.writeString(file, "第一版方案内容", StandardCharsets.UTF_8);

        Optional<String> hash = manager.autoCommit(file, "提交方案初稿");
        assertTrue(hash.isPresent(), "有内容变更时应产生提交");
        assertEquals(40, hash.get().length(), "commit hash 应为 40 位十六进制");
    }

    @Test
    @DisplayName("无内容变更时 autoCommit 返回 Optional.empty()")
    void autoCommitWithoutChangesShouldReturnEmpty() throws Exception {
        Path file = tempDir.resolve("report.docx");
        Files.writeString(file, "不变的报告内容", StandardCharsets.UTF_8);

        // 第一次提交
        Optional<String> firstHash = manager.autoCommit(file, "首次提交");
        assertTrue(firstHash.isPresent(), "第一次应有提交");

        // 第二次提交（文件未修改）
        Optional<String> secondHash = manager.autoCommit(file, "无变更提交");
        assertTrue(secondHash.isEmpty(), "无变更时不应产生空 commit");
    }

    @Test
    @DisplayName("反复修改同一文件，每次有变更才提交")
    void repeatedAutoCommitWithModifications() throws Exception {
        Path file = tempDir.resolve("notes.txt");

        Files.writeString(file, "版本 A", StandardCharsets.UTF_8);
        Optional<String> h1 = manager.autoCommit(file, null);
        assertTrue(h1.isPresent());

        // 无修改 → 跳过
        Optional<String> h1b = manager.autoCommit(file, null);
        assertTrue(h1b.isEmpty());

        // 修改后 → 提交
        Files.writeString(file, "版本 B", StandardCharsets.UTF_8);
        Optional<String> h2 = manager.autoCommit(file, null);
        assertTrue(h2.isPresent());

        // 再次修改 → 再次提交
        Files.writeString(file, "版本 C", StandardCharsets.UTF_8);
        Optional<String> h3 = manager.autoCommit(file, null);
        assertTrue(h3.isPresent());

        assertNotEquals(h1.get(), h2.get());
        assertNotEquals(h2.get(), h3.get());
    }

    @Test
    @DisplayName("提交信息自动生成时间戳格式")
    void autoCommitShouldGenerateTimestampMessage() throws Exception {
        Path file = tempDir.resolve("timesheet.xlsx");
        Files.writeString(file, "工时数据", StandardCharsets.UTF_8);

        Optional<String> hash = manager.autoCommit(file, null);
        assertTrue(hash.isPresent());

        List<VersionCommit> history = manager.getRevisionHistory("timesheet.xlsx");
        assertEquals(1, history.size());
        assertTrue(history.get(0).getMessage().startsWith("自动存档: "),
                "消息应以 '自动存档: ' 开头，实际: " + history.get(0).getMessage());
    }

    @Test
    @DisplayName("不存在的文件 → Optional.empty()")
    void autoCommitNonexistentFileShouldReturnEmpty() throws Exception {
        Path ghost = tempDir.resolve("ghost.docx");
        Optional<String> hash = manager.autoCommit(ghost, "ghost commit");
        assertTrue(hash.isEmpty());
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. getRevisionHistory — 文件版本历史
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("获取文件版本历史，按时间倒序")
    void shouldReturnReverseChronologicalHistory() throws Exception {
        Path file = tempDir.resolve("timeline.txt");

        // 创建 3 个版本
        Files.writeString(file, "v1", StandardCharsets.UTF_8);
        manager.autoCommit(file, "v1");
        Thread.sleep(50);

        Files.writeString(file, "v2", StandardCharsets.UTF_8);
        manager.autoCommit(file, "v2");
        Thread.sleep(50);

        Files.writeString(file, "v3", StandardCharsets.UTF_8);
        manager.autoCommit(file, "v3");

        List<VersionCommit> history = manager.getRevisionHistory("timeline.txt");
        assertEquals(3, history.size(), "应有 3 条提交记录");

        // 按时间倒序：最新在前
        assertEquals("v3", history.get(0).getMessage());
        assertEquals("v2", history.get(1).getMessage());
        assertEquals("v1", history.get(2).getMessage());
    }

    @Test
    @DisplayName("历史记录包含完整的 commit 元数据")
    void historyEntryShouldHaveCompleteMetadata() throws Exception {
        Path file = tempDir.resolve("metadata.docx");
        Files.writeString(file, "元数据测试", StandardCharsets.UTF_8);
        Optional<String> hash = manager.autoCommit(file, "元数据提交");
        assertTrue(hash.isPresent());

        List<VersionCommit> history = manager.getRevisionHistory("metadata.docx");
        assertEquals(1, history.size());

        VersionCommit vc = history.get(0);
        assertEquals(40, vc.getGitHash().length());
        assertEquals(7, vc.getShortHash().length());
        assertEquals(hash.get().substring(0, 7), vc.getShortHash());
        assertEquals("DocChain", vc.getAuthor());
        assertEquals("元数据提交", vc.getMessage());
        assertNotNull(vc.getTimestamp());
        assertTrue(vc.getFileList().contains("metadata.docx"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. rollbackToVersion — 文件回溯
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("回滚文件到指定历史版本")
    void shouldRollbackFileToSpecificVersion() throws Exception {
        Path file = tempDir.resolve("rollback-test.txt");

        // 版本 1
        Files.writeString(file, "原始内容-版本1", StandardCharsets.UTF_8);
        Optional<String> h1 = manager.autoCommit(file, "v1");

        // 版本 2
        Files.writeString(file, "修改后内容-版本2", StandardCharsets.UTF_8);
        Optional<String> h2 = manager.autoCommit(file, "v2");

        // 确认当前内容是 v2
        assertEquals("修改后内容-版本2", Files.readString(file));

        // 回滚到 v1
        manager.rollbackToVersion(h1.get(), "rollback-test.txt");
        assertEquals("原始内容-版本1", Files.readString(file),
                "回滚后文件内容应为版本 1");
    }

    @Test
    @DisplayName("回滚后文件在文件系统中真实可读")
    void rollbackFileShouldBeReadable() throws Exception {
        Path file = tempDir.resolve("readable.txt");
        Files.writeString(file, "AAAA", StandardCharsets.UTF_8);
        Optional<String> h1 = manager.autoCommit(file, "first");

        Files.writeString(file, "BBBB", StandardCharsets.UTF_8);
        manager.autoCommit(file, "second");

        manager.rollbackToVersion(h1.get(), "readable.txt");
        assertTrue(Files.isReadable(file));
        assertEquals("AAAA", Files.readString(file));
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. DocumentWatcher ↔ GitRepositoryManager 串联集成测试
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("集成: DocumentWatcher 防抖通知 → GitRepositoryManager 自动提交")
    void watcherToGitIntegration() throws Exception {
        manager.close(); // 用 DocumentWatcher 的 tempDir 重新初始化

        Path workDir = Files.createTempDirectory("docchain-integration-");
        GitRepositoryManager integMgr = GitRepositoryManager.init(workDir);

        try {
            // 收集被提交的文件列表
            List<String> committedFiles = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch latch = new CountDownLatch(2); // 期望 2 个文件各产生一次提交

            // 创建 DocumentWatcher，防抖 500ms
            DocumentWatcher watcher = new DocumentWatcher(500);
            watcher.register(workDir, false);

            // 绑定回调：防抖完成后 → Git 自动提交
            watcher.addListener(filePath -> {
                try {
                    Optional<String> hash = integMgr.autoCommit(filePath, null);
                    hash.ifPresent(h -> {
                        committedFiles.add(filePath.getFileName().toString());
                        latch.countDown();
                    });
                } catch (Exception e) {
                    fail("Auto-commit failed for: " + filePath, e);
                }
            });

            Thread watcherThread = new Thread(watcher::start, "integ-test-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();

            // 模拟 Office 保存行为：2 秒内连续写入文件 A（防抖聚合）
            Path fileA = workDir.resolve("budget-2025.xlsx");
            for (int i = 0; i < 5; i++) {
                Files.writeString(fileA, "Budget iteration " + i, StandardCharsets.UTF_8);
                Thread.sleep(80);
            }

            // 写入文件 B（与 A 独立防抖）
            Thread.sleep(200);
            Path fileB = workDir.resolve("meeting-notes.docx");
            for (int i = 0; i < 3; i++) {
                Files.writeString(fileB, "Notes iteration " + i, StandardCharsets.UTF_8);
                Thread.sleep(80);
            }

            // 等待防抖 + 提交完成
            boolean allCommitted = latch.await(10, TimeUnit.SECONDS);
            assertTrue(allCommitted, "应在 10 秒内完成 2 个文件的提交，实际完成: " + committedFiles.size());

            assertEquals(2, committedFiles.size(),
                    "应产生 2 次提交（每个文件 1 次），实际: " + committedFiles);

            // 验证 Git 历史
            assertFalse(integMgr.getRevisionHistory("budget-2025.xlsx").isEmpty(),
                    "budget-2025.xlsx 应有提交历史");
            assertFalse(integMgr.getRevisionHistory("meeting-notes.docx").isEmpty(),
                    "meeting-notes.docx 应有提交历史");

            watcher.stop();
            watcherThread.join(2000);
        } finally {
            integMgr.close();
            try {
                try (var s = Files.walk(workDir)) {
                    s.sorted(java.util.Comparator.reverseOrder())
                     .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                }
            } catch (IOException ignored) {}
        }
    }

    @Test
    @DisplayName("集成: 无实际变更时 Watcher 回调不应产生空提交")
    void watcherShouldNotCreateEmptyCommits() throws Exception {
        manager.close();

        Path workDir = Files.createTempDirectory("docchain-nop-");
        GitRepositoryManager integMgr = GitRepositoryManager.init(workDir);

        try {
            DocumentWatcher watcher = new DocumentWatcher(300);
            watcher.register(workDir, false);

            List<Path> notified = Collections.synchronizedList(new ArrayList<>());
            watcher.addListener(notified::add);

            Thread watcherThread = new Thread(watcher::start, "nop-test-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();

            Path file = workDir.resolve("static-file.docx");
            Files.writeString(file, "静态内容", StandardCharsets.UTF_8);

            // 等待防抖完成
            Thread.sleep(600);
            assertEquals(1, notified.size(), "应收到 1 次防抖通知");

            // 手动提交第 1 次
            Optional<String> h1 = integMgr.autoCommit(file, "首次");
            assertTrue(h1.isPresent());

            // 再次写入相同内容，防抖通知后不应产生新提交
            // 先停掉 watcher 以免 Windows 下文件锁定冲突
            watcher.stop();
            watcherThread.join(2000);

            Files.writeString(file, "静态内容", StandardCharsets.UTF_8);
            // 直接调用 autoCommit，不再依赖 watcher 回调
            Optional<String> h2 = integMgr.autoCommit(file, null);
            assertTrue(h2.isEmpty(), "相同内容不应产生新提交: " + h2.orElse(""));

            // 验证历史只有 1 条
            List<VersionCommit> history = integMgr.getRevisionHistory("static-file.docx");
            assertEquals(1, history.size(), "应该只有 1 条提交记录");

            watcher.stop();
            watcherThread.join(2000);
        } finally {
            integMgr.close();
            try {
                try (var s = Files.walk(workDir)) {
                    s.sorted(java.util.Comparator.reverseOrder())
                     .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                }
            } catch (IOException ignored) {}
        }
    }
}
