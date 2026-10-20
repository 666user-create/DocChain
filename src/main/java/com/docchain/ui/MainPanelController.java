package com.docchain.ui;

import com.docchain.core.DocumentWatcher;
import com.docchain.core.GitRepositoryManager;
import com.docchain.core.WatchRegistry;
import com.docchain.model.VersionCommit;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 版本时间线主面板 — 左侧目录/文件列表 + 右侧版本历史（恢复按钮）。
 *
 * <p>UI 只依赖 {@link WatchRegistry}：目录的增删、版本库的路由都由它负责。</p>
 */
public class MainPanelController {

    private static final Logger log = LoggerFactory.getLogger(MainPanelController.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final String ALL_DIRS = "全部目录";
    private static final String UNAVAILABLE = "（不可用）";

    private static final String CARD_STYLE =
            "-fx-background-color: white; -fx-padding: 10 14 10 14; " +
            "-fx-border-color: #e0e0e0; -fx-border-radius: 6; -fx-background-radius: 6;";

    /** 文件条目：属于哪个监听目录 + 相对路径 */
    private record FileRef(Path dir, String relPath) {}

    private final WatchRegistry registry;
    /** 目录下拉显示名 → 实际路径 */
    private final Map<String, Path> dirIndex = new LinkedHashMap<>();
    /** 文件列表显示名 → 条目 */
    private final Map<String, FileRef> fileIndex = new HashMap<>();

    private Stage stage;
    private ComboBox<String> dirCombo;
    private ListView<String> fileListView;
    private Label statusLabel;
    private Label timelineTitle;
    private VBox timelineContainer;

    private Path currentDir;          // null = 全部目录
    private FileRef selectedFile;
    private GitRepositoryManager currentRepo;

    public MainPanelController(WatchRegistry registry) {
        this.registry = registry;
    }

    private void ensureStage() {
        if (stage != null) return;
        stage = new Stage();
        buildUI();
    }

    private void buildUI() {
        stage.setTitle("DocChain - 版本时间线");
        stage.setMinWidth(860);
        stage.setMinHeight(560);

        // ── 左侧：目录选择 + 文件列表 ──
        Label dirTitle = new Label("监听目录");
        dirTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        dirCombo = new ComboBox<>();
        dirCombo.setMaxWidth(Double.MAX_VALUE);
        dirCombo.setTooltip(new Tooltip("选择要查看的文件夹"));
        dirCombo.valueProperty().addListener((obs, old, key) -> {
            Path selected = key == null ? null : dirIndex.get(key);
            currentDir = selected;
            fileListView.getSelectionModel().clearSelection();
            timelineTitle.setText("选择一个文件查看版本历史");
            timelineContainer.getChildren().clear();
            refreshFileList();
        });

        Button addBtn = new Button("+ 添加目录");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setStyle("-fx-background-color: #4A90D9; -fx-text-fill: white; -fx-background-radius: 4;");
        addBtn.setOnAction(e -> addDirectory());

        Button removeBtn = new Button("移除");
        removeBtn.setMaxWidth(Double.MAX_VALUE);
        removeBtn.setOnAction(e -> removeDirectory());

        HBox dirButtons = new HBox(6, addBtn, removeBtn);
        HBox.setHgrow(addBtn, Priority.ALWAYS);

        Label fileTitle = new Label("文件列表");
        fileTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        fileListView = new ListView<>();
        fileListView.setStyle("-fx-background-color: #fafafa;");
        fileListView.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, name) -> {
                    if (name != null) onFileSelected(name);
                });

        Button refreshBtn = new Button("刷新");
        refreshBtn.setMaxWidth(Double.MAX_VALUE);
        refreshBtn.setOnAction(e -> {
            refreshFileList();
            if (selectedFile != null && currentRepo != null) {
                loadTimeline(currentRepo, selectedFile.relPath());
            }
        });

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        VBox leftPanel = new VBox(6, dirTitle, dirCombo, dirButtons, fileTitle, fileListView, refreshBtn, statusLabel);
        leftPanel.setPadding(new Insets(12));
        VBox.setVgrow(fileListView, Priority.ALWAYS);

        // ── 右侧：时间线 ──
        timelineTitle = new Label("选择一个文件查看版本历史");
        timelineTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        timelineContainer = new VBox(8);
        timelineContainer.setPadding(new Insets(8));

        ScrollPane scrollPane = new ScrollPane(timelineContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: #f0f0f0; -fx-background: #f0f0f0;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox rightPanel = new VBox(10, timelineTitle, scrollPane);
        rightPanel.setPadding(new Insets(12));
        VBox.setVgrow(rightPanel, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setLeft(leftPanel);
        root.setCenter(rightPanel);

        stage.setScene(new Scene(root));
        stage.setOnCloseRequest(e -> { e.consume(); stage.hide(); });
    }

    public void show() {
        ensureStage();
        refreshDirCombo();
        refreshFileList();
        stage.show();
        stage.toFront();
    }

    public void hide() {
        if (stage != null) stage.hide();
    }

    // ═══════════════════════════════════════════════════════
    // 目录管理
    // ═══════════════════════════════════════════════════════

    /** 重建目录下拉框，尽量保留当前选中项。 */
    private void refreshDirCombo() {
        dirIndex.clear();
        List<Path> dirs = registry.getWatchedDirectories();

        // 同名目录用完整路径区分，避免两个 "文档" 分不清
        Map<String, Long> nameCounts = new LinkedHashMap<>();
        for (Path d : dirs) {
            nameCounts.merge(dirName(d), 1L, Long::sum);
        }

        for (Path d : dirs) {
            String key = nameCounts.get(dirName(d)) > 1 ? d.toString() : dirName(d);
            if (!Files.isDirectory(d)) key += UNAVAILABLE;
            dirIndex.put(key, d);
        }

        String previous = currentDir != null ? keyOf(currentDir) : null;
        List<String> items = new ArrayList<>();
        items.add(ALL_DIRS);
        items.addAll(dirIndex.keySet());
        dirCombo.getItems().setAll(items);

        String toSelect = previous != null && dirIndex.containsKey(previous)
                ? previous : ALL_DIRS;
        currentDir = ALL_DIRS.equals(toSelect) ? null : dirIndex.get(toSelect);
        dirCombo.setValue(toSelect);
    }

    private String keyOf(Path dir) {
        for (Map.Entry<String, Path> e : dirIndex.entrySet()) {
            if (e.getValue().equals(dir)) return e.getKey();
        }
        return null;
    }

    private static String dirName(Path dir) {
        Path name = dir.getFileName();
        return name != null ? name.toString() : dir.toString();
    }

    /**
     * 统一弹窗构造：标题固定 DocChain、无 header、正文自动换行（长提示不再被截断成省略号）。
     * 直接把设置好换行的 Label 作为内容节点交给面板，不依赖 DialogPane 内部惰性创建的节点。
     */
    private static Alert alertWithText(Alert.AlertType type, String text) {
        Alert alert = new Alert(type);
        alert.setTitle("DocChain");
        alert.setHeaderText(null);
        Label content = new Label(text);
        content.setWrapText(true);
        content.setMaxWidth(460);
        alert.getDialogPane().setContent(content);
        return alert;
    }

    private void addDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择要追踪的文件夹");
        File chosen = chooser.showDialog(stage);
        if (chosen == null) return;

        try {
            Path added = registry.addWatchDirectory(chosen.toPath());
            refreshDirCombo();
            // 选中新目录，直接展示它的文件
            String key = keyOf(added);
            if (key != null) dirCombo.setValue(key);
            else refreshFileList();
            statusLabel.setText("已开始监听: " + dirName(added) + "，正在为已有文件做初始存档…");
        } catch (Exception e) {
            log.warn("Add directory failed: {}", e.getMessage());
            alertWithText(Alert.AlertType.WARNING, e.getMessage()).showAndWait();
        }
    }

    private void removeDirectory() {
        if (currentDir == null) {
            alertWithText(Alert.AlertType.INFORMATION,
                    "请先在下拉框中选择一个具体目录，再点「移除」。").showAndWait();
            return;
        }

        Path dir = currentDir;
        Alert confirm = alertWithText(Alert.AlertType.CONFIRMATION,
                "停止监听 \"" + dirName(dir) + "\"？\n" +
                "已有的版本历史会保留在目录里的 .docchain-git 中，重新添加即可恢复。");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        registry.removeWatchDirectory(dir);
        currentDir = null;
        selectedFile = null;
        currentRepo = null;
        timelineTitle.setText("选择一个文件查看版本历史");
        timelineContainer.getChildren().clear();
        refreshDirCombo();
        refreshFileList();
    }

    // ═══════════════════════════════════════════════════════
    // 文件列表
    // ═══════════════════════════════════════════════════════

    private void refreshFileList() {
        fileIndex.clear();
        List<String> files = new ArrayList<>();

        List<Path> dirs = new ArrayList<>();
        if (currentDir != null) {
            dirs.add(currentDir);
        } else {
            dirs.addAll(registry.getWatchedDirectories());
        }

        int skippedDirs = 0;
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) { skippedDirs++; continue; }
            String prefix = currentDir == null ? dirName(dir) + "/" : "";
            try (Stream<Path> stream = Files.walk(dir, 30)) {
                stream.filter(Files::isRegularFile)
                        .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                        .filter(n -> !n.startsWith("."))
                        .filter(n -> !isIgnoredPath(n))
                        .filter(n -> !DocumentWatcher.isTemporaryOrSystemFile(Path.of(n)))
                        .sorted(String::compareToIgnoreCase)
                        .forEach(rel -> {
                            String display = prefix + rel;
                            // 极端重名时回退到完整路径前缀，保证唯一
                            if (fileIndex.containsKey(display)) {
                                display = dir + "/" + rel;
                            }
                            fileIndex.put(display, new FileRef(dir, rel));
                            files.add(display);
                        });
            } catch (IOException e) {
                log.warn("Failed to list files in {}: {}", dir, e.getMessage());
            }
        }

        fileListView.getItems().setAll(files);

        // 状态行 / 空态提示
        if (dirs.isEmpty()) {
            statusLabel.setText("还没有监听目录 — 点击「+ 添加目录」选择要追踪的文件夹");
        } else if (files.isEmpty()) {
            statusLabel.setText(skippedDirs > 0
                    ? "所选目录暂不可用（可能已移除或未插入存储设备）"
                    : "该目录下还没有文件");
        } else {
            statusLabel.setText(String.format("%d 个目录 · %d 个文件",
                    dirs.size() - skippedDirs, files.size()));
        }
        log.debug("Refreshed file list: {} files", files.size());
    }

    /** 相对路径中任意一级属于忽略目录（.git/.docchain-git/...）则过滤。 */
    private static boolean isIgnoredPath(String relPath) {
        for (String part : relPath.split("/")) {
            if (part.equals(".git") || part.equals(".svn") || part.equals("node_modules")
                    || part.equals("__pycache__") || part.equals(".docchain-git")) {
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════
    // 时间线
    // ═══════════════════════════════════════════════════════

    private void onFileSelected(String display) {
        FileRef ref = fileIndex.get(display);
        if (ref == null) return;
        selectedFile = ref;
        timelineTitle.setText("版本历史: " + display);

        currentRepo = registry.getRepo(ref.dir());
        if (currentRepo == null) {
            timelineContainer.getChildren().clear();
            Label unavailable = new Label("该目录暂不可用，无法读取版本历史。");
            unavailable.setStyle("-fx-text-fill: #d32f2f; -fx-padding: 20;");
            timelineContainer.getChildren().add(unavailable);
            return;
        }
        loadTimeline(currentRepo, ref.relPath());
    }

    private void loadTimeline(GitRepositoryManager repo, String relativePath) {
        timelineContainer.getChildren().clear();
        try {
            List<VersionCommit> history = repo.getRevisionHistory(relativePath);

            if (history.isEmpty()) {
                Label emptyLabel = new Label("暂无版本记录 — 修改并保存该文件后会自动存档");
                emptyLabel.setStyle("-fx-text-fill: #999; -fx-padding: 20;");
                emptyLabel.setWrapText(true);
                timelineContainer.getChildren().add(emptyLabel);
                return;
            }

            for (VersionCommit vc : history) {
                timelineContainer.getChildren().add(buildVersionCard(vc, relativePath));
            }
        } catch (Exception e) {
            log.error("Failed to load timeline for: {}", relativePath, e);
            Label errLabel = new Label("加载失败: " + e.getMessage());
            errLabel.setStyle("-fx-text-fill: #d32f2f;");
            timelineContainer.getChildren().add(errLabel);
        }
    }

    private VBox buildVersionCard(VersionCommit vc, String relativePath) {
        Circle dot = new Circle(5);
        dot.setFill(vc.isAutoCommit() ? Color.web("#5B9BD5") : Color.web("#4CAF50"));

        Label timeLabel = new Label(vc.getTimestamp().format(TIME_FMT));
        timeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999;");

        Label hashLabel = new Label(vc.getShortHash());
        hashLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #bbb;");

        String displayText = vc.getMessage();
        if (displayText.length() > 80) displayText = displayText.substring(0, 80) + "...";
        Label msgLabel = new Label(displayText);
        msgLabel.setStyle("-fx-font-size: 13px;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button rollbackBtn = new Button("恢复");
        rollbackBtn.setStyle(
                "-fx-font-size: 11px; -fx-background-color: #4A90D9; -fx-text-fill: white; " +
                "-fx-background-radius: 4; -fx-padding: 4 12 4 12; -fx-cursor: hand;");
        rollbackBtn.setOnAction(e -> rollback(vc.getGitHash(), relativePath, vc));

        HBox topRow = new HBox(6, dot, timeLabel, hashLabel, spacer, rollbackBtn);
        topRow.setAlignment(Pos.CENTER_LEFT);

        HBox messageRow = new HBox(msgLabel);
        messageRow.setPadding(new Insets(2, 0, 0, 0));

        VBox card = new VBox(3, topRow, messageRow);
        card.setPadding(new Insets(8, 12, 8, 12));
        card.setStyle(CARD_STYLE);

        return card;
    }

    private void rollback(String commitId, String relativePath, VersionCommit vc) {
        if (currentRepo == null) return;
        try {
            currentRepo.rollbackToVersion(commitId, relativePath);
            String time = vc.getTimestamp().format(SHORT_TIME_FMT);
            log.info("Rolled back '{}' to {}", relativePath, commitId.substring(0, 7));
            Platform.runLater(() ->
                    alertWithText(Alert.AlertType.INFORMATION, "已恢复至 " + time + " 的版本").show());
            loadTimeline(currentRepo, relativePath);
        } catch (Exception e) {
            log.error("Rollback failed: {}", e.getMessage());
            Platform.runLater(() ->
                    alertWithText(Alert.AlertType.ERROR, "恢复失败: " + e.getMessage()).show());
        }
    }
}
