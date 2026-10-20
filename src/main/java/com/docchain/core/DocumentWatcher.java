package com.docchain.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 面向办公文档的文件监听器。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>基于 JDK {@link WatchService}，零第三方依赖</li>
 *   <li>按文件粒度的防抖（debounce）：同一文件在 2000ms 内无新事件才触发通知</li>
 *   <li>内置 Office 临时文件过滤（~$*.docx、*.tmp、*.swp 等）</li>
 *   <li>通过 {@link OnFileSavedListener} 回调通知上层模块</li>
 *   <li>支持递归或单层目录监听</li>
 * </ul>
 */
public class DocumentWatcher {

    private static final Logger log = LoggerFactory.getLogger(DocumentWatcher.class);

    /** 默认防抖时间（毫秒） */
    public static final long DEFAULT_DEBOUNCE_MS = 2000;

    // ── 临时文件过滤规则 ──────────────────────────────────────────────
    private static final Set<String> TEMP_PREFIXES = Set.of("~$");
    private static final Set<String> TEMP_EXTENSIONS = Set.of(".tmp", ".swp", ".swx", ".lock", ".part", ".crdownload");
    // Windows 下 Office 会生成以 "~$" 开头的锁定文件，macOS 下会生成 "._" 前缀或 .DS_Store
    private static final Set<String> SYSTEM_FILES = Set.of(".DS_Store", "Thumbs.db", ".bridge");

    // ── 回调接口 ─────────────────────────────────────────────────────
    /**
     * 文件"防抖保存"完成后的通知回调。
     */
    @FunctionalInterface
    public interface OnFileSavedListener {
        /**
         * @param filePath 已确认保存完成的文件绝对路径
         */
        void onFileSaved(Path filePath);
    }

    // ── 内部状态 ─────────────────────────────────────────────────────
    private final WatchService watchService;
    private final ScheduledExecutorService scheduler;
    private final long debounceMs;

    /** path → 对应的 WatchKey（用于取消注册已删除的目录） */
    private final Map<Path, WatchKey> dirKeys = new ConcurrentHashMap<>();
    /** path → 当前排期的防抖任务 */
    private final Map<Path, ScheduledFuture<?>> debounceFutures = new ConcurrentHashMap<>();
    /** 已注册的监听器列表 */
    private final List<OnFileSavedListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean running;

    public DocumentWatcher() throws IOException {
        this(DEFAULT_DEBOUNCE_MS);
    }

    public DocumentWatcher(long debounceMs) throws IOException {
        this.debounceMs = debounceMs;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "docchain-debounce");
            t.setDaemon(true);
            return t;
        });
    }

    // ── 公开 API ─────────────────────────────────────────────────────

    /**
     * 注册监听目录。
     * @param dir       要监听的目录
     * @param recursive 是否递归监听子目录
     */
    public void register(Path dir, boolean recursive) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + dir);
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                if (isIgnoredDir(d)) return FileVisitResult.SKIP_SUBTREE;
                WatchKey key = d.register(watchService,
                        new WatchEvent.Kind<?>[]{ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE});
                dirKeys.put(d, key);
                log.debug("Registered: {}", d);
                if (!recursive) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
        });
        log.info("DocumentWatcher registered: {} (recursive={})", dir, recursive);
    }

    /**
     * 注销监听目录（含其所有已注册的子目录），并取消其下所有待触发的防抖任务。
     * 注销后，该目录下的文件变更不会再触发回调。
     *
     * @param dir 之前通过 {@link #register} 注册过的目录
     */
    public void unregister(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        for (Map.Entry<Path, WatchKey> entry : new java.util.ArrayList<>(dirKeys.entrySet())) {
            if (entry.getKey().startsWith(abs)) {
                entry.getValue().cancel();
                dirKeys.remove(entry.getKey());
            }
        }
        for (Path pending : new java.util.ArrayList<>(debounceFutures.keySet())) {
            if (pending.startsWith(abs)) {
                cancelDebounce(pending);
            }
        }
        log.info("DocumentWatcher unregistered: {}", abs);
    }

    /**
     * 添加文件保存监听器（线程安全）。
     */
    public void addListener(OnFileSavedListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除文件保存监听器。
     */
    public void removeListener(OnFileSavedListener listener) {
        listeners.remove(listener);
    }

    /**
     * 启动监听循环。此方法会阻塞当前线程，建议在独立线程中调用。
     */
    public void start() {
        running = true;
        log.info("DocumentWatcher started (debounce={}ms)", debounceMs);
        while (running) {
            try {
                WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) continue;

                Path watchDir = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    Path relative = ((WatchEvent<Path>) event).context();
                    Path absolute = watchDir.resolve(relative);

                    if (isTemporaryOrSystemFile(absolute)) {
                        log.trace("Ignored temp/system file: {}", absolute.getFileName());
                        continue;
                    }

                    if (kind == ENTRY_DELETE) {
                        cancelDebounce(absolute);
                        continue;
                    }

                    // 目录条目：只负责注册/忽略，绝不排防抖。
                    // 目录不是可提交的文件，它的 mtime 变化（比如 .docchain-git 内部写入）
                    // 一旦触发回调只会让上层对着一个目录路径白白报错。
                    if (Files.isDirectory(absolute)) {
                        if (kind == ENTRY_CREATE && !isIgnoredDir(absolute)) {
                            try {
                                WatchKey newKey = absolute.register(watchService,
                                        new WatchEvent.Kind<?>[]{ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE});
                                dirKeys.put(absolute, newKey);
                                log.debug("Auto-registered new directory: {}", absolute);
                            } catch (IOException e) {
                                log.warn("Failed to register new directory: {}", absolute, e);
                            }
                        }
                        continue;
                    }

                    // 文件的 ENTRY_CREATE / ENTRY_MODIFY → 重置防抖计时
                    scheduleDebounce(absolute);
                }

                if (!key.reset()) {
                    dirKeys.remove(watchDir);
                    log.debug("WatchKey invalid: {}", watchDir);
                }
            } catch (ClosedWatchServiceException e) {
                // 正常关闭时 watchService.close() 触发，直接退出
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("DocumentWatcher stopped");
    }

    /**
     * 优雅关闭。
     */
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        try {
            watchService.close();
        } catch (IOException e) {
            log.error("Error closing WatchService", e);
        }
    }

    // ── 防抖逻辑 ─────────────────────────────────────────────────────

    private void scheduleDebounce(Path path) {
        // 取消该文件的旧防抖任务
        cancelDebounce(path);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            debounceFutures.remove(path);
            log.debug("Debounce fired for: {}", path.getFileName());
            notifyListeners(path);
        }, debounceMs, TimeUnit.MILLISECONDS);

        debounceFutures.put(path, future);
        log.trace("Debounce scheduled: {} ({}ms)", path.getFileName(), debounceMs);
    }

    private void cancelDebounce(Path path) {
        ScheduledFuture<?> future = debounceFutures.remove(path);
        if (future != null) {
            future.cancel(false);
            log.trace("Debounce cancelled: {}", path.getFileName());
        }
    }

    private void notifyListeners(Path path) {
        for (OnFileSavedListener listener : listeners) {
            try {
                listener.onFileSaved(path);
            } catch (Exception e) {
                log.error("Listener error for file '{}'", path, e);
            }
        }
    }

    // ── 过滤规则 ─────────────────────────────────────────────────────

    /**
     * 判断是否是 Office 临时文件、锁定文件或系统文件，不应被监听。
     */
    public static boolean isTemporaryOrSystemFile(Path path) {
        // 目录不检查文件名规则（但递归时 .git 等会走 isIgnoredDir）
        if (Files.isDirectory(path)) return false;

        String name = path.getFileName() != null ? path.getFileName().toString() : "";

        // 系统元数据文件
        if (SYSTEM_FILES.contains(name)) return true;

        // Office 锁定文件前缀：~$（~ 前缀不过滤，避免误杀 ~backup 等合法文件）
        for (String prefix : TEMP_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }

        // 隐藏文件（. 开头），但不过滤 .gitignore 这类用户可能需要的
        // Office 锁定文件典型特征：~$ 开头，已经被上面捕获

        // 临时文件后缀
        String lower = name.toLowerCase();
        for (String ext : TEMP_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }

        // Office 自动恢复文件：文件名以 "~" 开头且不含扩展名（如 ~WRL0001.tmp）
        // .tmp 已经被上面捕获

        return false;
    }

    public static boolean isIgnoredDir(Path dir) {
        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
        return name.equals(".git") || name.equals(".svn") || name.equals("node_modules")
                || name.equals("__pycache__") || name.equals(GitRepositoryManager.GIT_DIR_NAME);
    }

    // ── 暴露状态供测试使用 ────────────────────────────────────────────

    int pendingDebounceCount() {
        return debounceFutures.size();
    }

    int registeredDirCount() {
        return dirKeys.size();
    }
}
