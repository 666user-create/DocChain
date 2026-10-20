package com.docchain;

import com.docchain.core.DocumentWatcher;
import com.docchain.core.GitRepositoryManager;
import com.docchain.core.WatchDirectoryStore;
import com.docchain.core.WatchRegistry;
import com.docchain.ui.MainPanelController;
import com.docchain.ui.SystemTrayManager;
import com.docchain.util.PlatformUtils;
import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DocChain — 本地静默文档版本管理器。
 *
 * <h3>启动流程</h3>
 * <p>加载监听目录列表 → 为每个目录打开/初始化 {@code .docchain-git} 版本库 →
 * 注册文件监听 → 系统托盘驻留。</p>
 *
 * <h3>多目录编排</h3>
 * <p>每个监听目录一个独立版本库（{@link GitRepositoryManager}），共享一个
 * {@link DocumentWatcher}。文件保存回调按"最长前缀匹配"路由到对应版本库。</p>
 */
public class MainApp extends Application implements WatchRegistry {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);
    private static final DateTimeFormatter IMPORT_MSG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 监听目录 → 独立版本库（插入序即展示序） */
    private final Map<Path, GitRepositoryManager> repos = new LinkedHashMap<>();
    /** 监听目录列表（与 store 文件一致，含暂不可用目录） */
    private final List<Path> watchedDirs = new ArrayList<>();
    private final Object lock = new Object();

    private Path defaultWorkspace;
    private WatchDirectoryStore store;
    private DocumentWatcher watcher;
    private Thread watcherThread;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.hide();

        try {
            Path appDataDir = PlatformUtils.getAppDataDir();
            Files.createDirectories(appDataDir);
            store = new WatchDirectoryStore(appDataDir.resolve("watched-directories.txt"));

            synchronized (lock) {
                watchedDirs.addAll(store.load());
                if (watchedDirs.isEmpty()) {
                    // 首次启动：创建默认 workspace，保证界面不是空的
                    defaultWorkspace = appDataDir.resolve("workspace");
                    Files.createDirectories(defaultWorkspace);
                    watchedDirs.add(defaultWorkspace);
                    store.save(watchedDirs);
                }
            }

            // 文件监听（防抖 + 过滤）→ 路由到对应版本库自动提交
            watcher = new DocumentWatcher();
            watcher.addListener(this::onFileSaved);

            synchronized (lock) {
                for (Path dir : new ArrayList<>(watchedDirs)) {
                    openRepoAndWatch(dir);
                }
            }

            watcherThread = new Thread(watcher::start, "docchain-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();

            // 消费旧版本右键菜单留下的待监听队列（文件 → 取其所在目录）
            consumePendingWatchQueue(appDataDir);

            // UI
            MainPanelController mainPanel = new MainPanelController(this);
            SystemTrayManager trayManager = new SystemTrayManager(mainPanel);
            trayManager.show();

            // 调试开关：DOCCHAIN_SHOW_PANEL=1 或 -Ddocchain.showPanel=true 时直接打开主面板
            if (Boolean.getBoolean("docchain.showPanel")
                    || "1".equals(System.getenv("DOCCHAIN_SHOW_PANEL"))) {
                javafx.application.Platform.runLater(mainPanel::show);
            }

            log.info("DocChain started — watching {} director{}", watchedDirs.size(),
                    watchedDirs.size() == 1 ? "" : "ies");
        } catch (Exception e) {
            log.error("Failed to start DocChain", e);
            System.exit(1);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // WatchRegistry 实现
    // ══════════════════════════════════════════════════════════════

    @Override
    public List<Path> getWatchedDirectories() {
        synchronized (lock) {
            return new ArrayList<>(watchedDirs);
        }
    }

    @Override
    public GitRepositoryManager getRepo(Path dir) {
        if (dir == null) return null;
        synchronized (lock) {
            return repos.get(dir.toAbsolutePath().normalize());
        }
    }

    @Override
    public Path addWatchDirectory(Path rawDir) throws Exception {
        Path dir = rawDir.toAbsolutePath().normalize();

        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("所选路径不是文件夹: " + dir);
        }
        synchronized (lock) {
            if (watchedDirs.contains(dir)) {
                throw new IllegalArgumentException("该目录已在监听列表中");
            }
            for (Path existing : watchedDirs) {
                if (dir.startsWith(existing)) {
                    throw new IllegalArgumentException(
                            "该目录已包含在上级监听目录中: " + displayName(existing));
                }
                if (existing.startsWith(dir)) {
                    throw new IllegalArgumentException(
                            "该目录包含已监听的子目录: " + displayName(existing));
                }
            }

            // 先注册监听 + 初始化版本库，成功后才落盘
            openRepoAndWatch(dir);
            if (getRepoUnsafe(dir) == null) {
                throw new IllegalStateException("版本库初始化失败，请查看日志: " + dir);
            }
            watchedDirs.add(dir);
            store.save(watchedDirs);
            log.info("Added watch directory: {}", dir);
        }

        // 后台为目录内已有文件建立基线存档（回滚的起点）
        GitRepositoryManager repo = getRepo(dir);
        if (repo != null) {
            importExistingFilesAsync(repo);
        }
        return dir;
    }

    @Override
    public void removeWatchDirectory(Path rawDir) {
        Path dir = rawDir.toAbsolutePath().normalize();
        synchronized (lock) {
            if (!watchedDirs.remove(dir)) return;
            if (watcher != null) watcher.unregister(dir);
            GitRepositoryManager repo = repos.remove(dir);
            if (repo != null) repo.close();
            store.save(watchedDirs);
            log.info("Removed watch directory: {}", dir);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 内部编排
    // ══════════════════════════════════════════════════════════════

    /** 打开（不存在则初始化）版本库并注册监听；目录不可用时记录警告而不中断启动。调用方需持有 lock。 */
    private void openRepoAndWatch(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("Watch directory not available (skipped): {}", dir);
            return;
        }
        try {
            GitRepositoryManager repo = GitRepositoryManager.init(dir);
            repos.put(dir, repo);
            watcher.register(dir, true);
        } catch (Exception e) {
            log.error("Failed to open/watch directory: {}", dir, e);
        }
    }

    private GitRepositoryManager getRepoUnsafe(Path dir) {
        return repos.get(dir);
    }

    /** 防抖回调：把文件路由到"最长前缀匹配"的版本库做增量提交。 */
    private void onFileSaved(Path file) {
        GitRepositoryManager repo;
        synchronized (lock) {
            repo = findRepoForFile(file);
        }
        if (repo == null) {
            log.debug("No watch repository for: {}", file);
            return;
        }
        try {
            repo.autoCommit(file, null);
        } catch (Exception e) {
            log.error("Auto-commit failed: {}", file, e);
        }
    }

    private GitRepositoryManager findRepoForFile(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        GitRepositoryManager best = null;
        int bestDepth = -1;
        for (Map.Entry<Path, GitRepositoryManager> e : repos.entrySet()) {
            if (abs.startsWith(e.getKey()) && e.getKey().getNameCount() > bestDepth) {
                best = e.getValue();
                bestDepth = e.getKey().getNameCount();
            }
        }
        return best;
    }

    /** 对目录内已有文件逐个做基线提交（后台线程，逐文件增量，与监听回调共用同一把仓库锁）。 */
    private void importExistingFilesAsync(GitRepositoryManager repo) {
        Thread t = new Thread(() -> {
            Path root = repo.getWorkingDir();
            String message = "初始存档: " + LocalDateTime.now().format(IMPORT_MSG_FMT);
            int[] count = {0};
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                        if (DocumentWatcher.isIgnoredDir(d)) return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()
                                && !DocumentWatcher.isTemporaryOrSystemFile(file)
                                && !file.getFileName().toString().startsWith(".")) {
                            try {
                                if (repo.autoCommit(file, message).isPresent()) count[0]++;
                            } catch (Exception e) {
                                log.debug("Import skipped {}: {}", file, e.getMessage());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception e) {
                log.warn("Initial import failed for {}: {}", root, e.getMessage());
            }
            log.info("Initial import of {}: {} file(s) archived", root, count[0]);
        }, "docchain-import");
        t.setDaemon(true);
        t.start();
    }

    static String displayName(Path dir) {
        Path name = dir.getFileName();
        return name != null ? name.toString() : dir.toString();
    }

    /**
     * 消费 {@code pending-watch.txt}（历史上由右键菜单"用 DocChain 追踪"写入的队列）。
     *
     * <p>队列里可能是文件夹，也可能是单个文件（取其所在文件夹监听）。
     * 无论条目是否成功，处理完即清空队列，避免重复消费。</p>
     */
    private void consumePendingWatchQueue(Path appDataDir) {
        Path queue = appDataDir.resolve("pending-watch.txt");
        if (!Files.isRegularFile(queue)) return;

        List<String> lines;
        try {
            lines = readQueueLines(queue);
        } catch (Exception e) {
            log.warn("Cannot read pending watch queue: {}", e.getMessage());
            return;
        }

        int added = 0;
        for (String line : lines) {
            String entry = line.trim();
            if (entry.isEmpty()) continue;
            try {
                Path p = Path.of(entry);
                Path target;
                if (Files.isDirectory(p)) {
                    target = p;
                } else if (Files.isRegularFile(p)) {
                    target = p.getParent();
                } else {
                    log.warn("Pending watch entry no longer exists: {}", entry);
                    continue;
                }
                addWatchDirectory(target);
                added++;
                log.info("Pending watch consumed: {} → {}", entry, target);
            } catch (Exception e) {
                // 重复/嵌套/失效等都只是跳过，不影响启动
                log.info("Pending watch entry skipped ({}): {}", entry, e.getMessage());
            }
        }

        try {
            Files.deleteIfExists(queue);
        } catch (Exception e) {
            log.warn("Cannot clear pending watch queue: {}", e.getMessage());
        }
        if (added > 0) log.info("Pending watch queue consumed: {} director{} added",
                added, added == 1 ? "y" : "ies");
    }

    /**
     * 读取旧版本队列文件：先按严格 UTF-8 解码，失败则回退 GBK
     * （早期版本用系统默认字符集写入，中文 Windows 即 GBK）。
     */
    private static List<String> readQueueLines(Path queue) throws Exception {
        byte[] bytes = Files.readAllBytes(queue);
        java.nio.charset.Charset charset = java.nio.charset.StandardCharsets.UTF_8;
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            charset = java.nio.charset.Charset.forName("GBK");
        }
        List<String> lines = new ArrayList<>();
        for (String line : new String(bytes, charset).split("\\R")) {
            if (!line.trim().isEmpty()) lines.add(line);
        }
        return lines;
    }

    @Override
    public void stop() {
        log.info("DocChain shutting down...");
        if (watcher != null) watcher.stop();
        if (watcherThread != null) {
            try { watcherThread.join(2000); } catch (InterruptedException ignored) {}
        }
        synchronized (lock) {
            for (GitRepositoryManager repo : repos.values()) {
                try { repo.close(); } catch (Exception e) {
                    log.warn("Error closing repository: {}", e.getMessage());
                }
            }
            repos.clear();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
