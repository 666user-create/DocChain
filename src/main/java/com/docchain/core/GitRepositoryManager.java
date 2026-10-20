package com.docchain.core;

import com.docchain.model.VersionCommit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * DocChain 专用的 Git 仓库管理器。
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li>Git 数据存储在 {@code .docchain-git} 目录，而非标准的 {@code .git}，
 *       避免与开发者工具冲突，且对办公用户不可见。</li>
 *   <li>所有操作均针对单个文件进行增量提交。</li>
 *   <li>自动提交前检查实际变更，无变更则放弃，避免产生空 commit。</li>
 * </ul>
 */
public class GitRepositoryManager {

    private static final Logger log = LoggerFactory.getLogger(GitRepositoryManager.class);

    /** DocChain 专用 Git 目录名 */
    public static final String GIT_DIR_NAME = ".docchain-git";

    private final Path workingDir;
    private final Path gitDir;
    private final Repository repo;
    private final Git git;

    private GitRepositoryManager(Path workingDir, Repository repo) {
        this.workingDir = workingDir.toAbsolutePath().normalize();
        this.gitDir = this.workingDir.resolve(GIT_DIR_NAME);
        this.repo = repo;
        this.git = new Git(repo);
    }

    // ══════════════════════════════════════════════════════════════════
    // 工厂方法
    // ══════════════════════════════════════════════════════════════════

    /**
     * 打开一个已有的 DocChain Git 仓库。若不存在则抛出异常。
     */
    public static GitRepositoryManager open(Path workingDir) throws IOException {
        Path gitDir = workingDir.toAbsolutePath().normalize().resolve(GIT_DIR_NAME);
        if (!Files.isDirectory(gitDir)) {
            throw new IOException("Not a DocChain repository: " + workingDir);
        }
        Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .setWorkTree(workingDir.toFile())
                .build();
        log.info("Opened DocChain repository: {}", workingDir);
        return new GitRepositoryManager(workingDir, repo);
    }

    /**
     * 在指定工作目录下初始化一个新的 DocChain Git 仓库（.docchain-git）。
     */
    public static GitRepositoryManager init(Path workingDir) throws IOException, GitAPIException {
        Path gitDir = workingDir.toAbsolutePath().normalize().resolve(GIT_DIR_NAME);

        if (Files.isDirectory(gitDir)) {
            return open(workingDir);
        }

        Repository repo = Git.init()
                .setDirectory(workingDir.toFile())
                .setGitDir(gitDir.toFile())
                .call()
                .getRepository();

        // 为 DocChain 仓库设置一个空的初始提交，方便后续 diff 操作
        try (Git g = new Git(repo)) {
            // 创建一个 .docchain 标记文件并提交，作为仓库的根提交
            Path marker = workingDir.resolve(".docchain");
            Files.writeString(marker, "DocChain repository marker");
            g.add().addFilepattern(".docchain").call();
            g.commit()
                    .setMessage("DocChain repository initialized")
                    .setAuthor("DocChain", "docchain@local")
                    .call();
        }

        log.info("Initialized DocChain repository: {}", workingDir);
        return new GitRepositoryManager(workingDir, repo);
    }

    // ══════════════════════════════════════════════════════════════════
    // 核心操作
    // ══════════════════════════════════════════════════════════════════

    /**
     * 对指定文件执行自动增量提交（无用户备注/批次）。
     */
    public Optional<String> autoCommit(Path file, String defaultMessage) throws IOException, GitAPIException {
        return autoCommit(file, defaultMessage, null, null);
    }

    /**
     * 对指定文件执行自动增量提交。
     *
     * @param file           要提交的文件（绝对路径或相对于工作目录的路径）
     * @param defaultMessage 提交信息（可为 null，则自动生成时间戳消息）
     * @param userNote       用户备注（可选）
     * @param batchId        批次 ID（可选）
     * @return 若实际产生了提交则返回 commit SHA-1；若无变更则返回 {@link Optional#empty()}
     */
    public synchronized Optional<String> autoCommit(Path file, String defaultMessage,
                                       String userNote, String batchId) throws IOException, GitAPIException {
        String relativePath = toRelativePath(file);
        if (relativePath == null || relativePath.isEmpty()) {
            log.warn("File is outside working directory: {}", file);
            return Optional.empty();
        }

        Path absolute = workingDir.resolve(relativePath);
        if (!Files.exists(absolute)) {
            log.warn("File does not exist: {}", absolute);
            return Optional.empty();
        }

        // 1. 先检查工作区文件相对于 HEAD 是否有实际内容变更
        if (!hasWorkingTreeChanges(relativePath)) {
            log.debug("No changes for: {}", relativePath);
            return Optional.empty();
        }

        // 2. 有变更 → 暂存 + 提交
        git.add().addFilepattern(relativePath).call();

        // 3. 生成提交信息并提交
        String message = (defaultMessage != null && !defaultMessage.isBlank())
                ? defaultMessage
                : "自动存档: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        RevCommit commit = git.commit()
                .setMessage(message)
                .setAuthor("DocChain", "docchain@local")
                .call();

        log.info("Committed: {} → {} ({} files)", commit.abbreviate(7).name(), message, 1);
        return Optional.of(commit.getName());
    }

    /**
     * 获取某个文件的所有版本提交记录，按时间倒序排列。
     *
     * @param relativeFilePath 相对于工作目录的文件路径
     * @return 提交记录列表（最新的在前）
     */
    public synchronized List<VersionCommit> getRevisionHistory(String relativeFilePath) throws IOException, GitAPIException {
        List<VersionCommit> history = new ArrayList<>();

        Iterable<RevCommit> commits = git.log()
                .addPath(relativeFilePath)
                .call();

        for (RevCommit c : commits) {
            VersionCommit vc = new VersionCommit();
            vc.setGitHash(c.getName());
            vc.setMessage(c.getShortMessage());
            vc.setAuthor(c.getAuthorIdent().getName());
            vc.setTimestamp(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(c.getCommitTime()), ZoneId.systemDefault()));
            vc.setFilesChanged(1);
            vc.setFileList(List.of(relativeFilePath));
            vc.setAutoCommit(c.getShortMessage().startsWith("自动存档"));

            history.add(vc);
        }

        return history; // git.log().addPath() 默认返回时间倒序
    }

    /**
     * 获取文件在指定 commit 时的内容（byte[]）。
     */
    public byte[] getFileContentAtCommit(String commitId, String relativeFilePath) throws IOException {
        try (RevWalk revWalk = new RevWalk(repo)) {
            ObjectId resolvedId = repo.resolve(commitId);
            if (resolvedId == null) {
                throw new IOException("Invalid commit ID: " + commitId);
            }
            RevCommit commit = revWalk.parseCommit(resolvedId);
            RevTree tree = commit.getTree();

            try (TreeWalk treeWalk = new TreeWalk(repo)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilter.create(relativeFilePath));

                if (!treeWalk.next()) {
                    throw new IOException("File not found in commit " + commitId + ": " + relativeFilePath);
                }

                ObjectId blobId = treeWalk.getObjectId(0);
                ObjectLoader loader = repo.open(blobId);
                return loader.getBytes();
            }
        }
    }

    /**
     * 将指定文件回滚到某个历史版本，并自动提交以记录此次回滚操作。
     *
     * @param commitId         目标 commit 的 SHA-1（完整或缩写均可）
     * @param relativeFilePath 相对于工作目录的文件路径
     */
    public synchronized void rollbackToVersion(String commitId, String relativeFilePath) throws IOException {
        Path absolute = workingDir.resolve(relativeFilePath);
        byte[] content = getFileContentAtCommit(commitId, relativeFilePath);

        Files.createDirectories(absolute.getParent());
        try (FileOutputStream fos = new FileOutputStream(absolute.toFile())) {
            fos.write(content);
        }

        log.info("Rolled back '{}' to commit {}", relativeFilePath, commitId);

        // 回滚后立即创建新提交，确保操作在版本历史中可见
        try {
            autoCommit(absolute, "回滚至版本: " + commitId.substring(0, Math.min(7, commitId.length())));
        } catch (GitAPIException e) {
            log.warn("Rollback succeeded but auto-commit failed: {}", e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 内部辅助
    // ══════════════════════════════════════════════════════════════════

    /**
     * 检查指定文件在工作区相对于 HEAD 是否有实际内容变更。
     * 使用 byte[] 逐字节比对，兼容二进制文件（.docx/.xlsx/.pptx 等）。
     */
    private boolean hasWorkingTreeChanges(String relativePath) throws IOException {
        Path absolute = workingDir.resolve(relativePath);
        if (!Files.exists(absolute)) return false;

        try (RevWalk revWalk = new RevWalk(repo)) {
            ObjectId headId = repo.resolve(Constants.HEAD);
            if (headId == null) return true;

            RevCommit headCommit = revWalk.parseCommit(headId);
            RevTree headTree = headCommit.getTree();

            byte[] headBytes = null;
            try (TreeWalk treeWalk = new TreeWalk(repo)) {
                treeWalk.addTree(headTree);
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilter.create(relativePath));
                if (treeWalk.next()) {
                    ObjectId headBlobId = treeWalk.getObjectId(0);
                    headBytes = repo.open(headBlobId).getBytes();
                }
            }

            byte[] workingBytes = Files.readAllBytes(absolute);

            if (headBytes != null && Arrays.equals(headBytes, workingBytes)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将绝对路径转为相对于工作目录的路径。
     * 若路径不在工作目录下，返回 null。
     */
    private String toRelativePath(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(workingDir)) {
            return workingDir.relativize(abs).toString().replace('\\', '/');
        }
        return null;
    }

    public Path getWorkingDir() { return workingDir; }
    public Path getGitDir() { return gitDir; }

    /**
     * 关闭仓库，释放资源。
     */
    public void close() {
        git.close();
        repo.close();
    }
}
