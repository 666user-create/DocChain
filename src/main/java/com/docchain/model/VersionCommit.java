package com.docchain.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一次版本提交记录，对应 Git 中的一个 commit。
 */
public class VersionCommit {

    private String id;
    private String repositoryId;
    private String gitHash;
    private String shortHash;
    private String message;
    private String author;
    private LocalDateTime timestamp;
    private int filesChanged;
    private List<String> fileList;
    private boolean isAutoCommit;

    public VersionCommit() {
        this.id = UUID.randomUUID().toString();
        this.fileList = new ArrayList<>();
    }

    public VersionCommit(String repositoryId, String gitHash, String message, String author,
                         LocalDateTime timestamp, int filesChanged, boolean isAutoCommit) {
        this();
        this.repositoryId = repositoryId;
        this.gitHash = gitHash;
        this.shortHash = gitHash != null && gitHash.length() >= 7 ? gitHash.substring(0, 7) : gitHash;
        this.message = message;
        this.author = author;
        this.timestamp = timestamp;
        this.filesChanged = filesChanged;
        this.isAutoCommit = isAutoCommit;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }

    public String getGitHash() { return gitHash; }
    public void setGitHash(String gitHash) {
        this.gitHash = gitHash;
        this.shortHash = gitHash != null && gitHash.length() >= 7 ? gitHash.substring(0, 7) : gitHash;
    }

    public String getShortHash() { return shortHash; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public int getFilesChanged() { return filesChanged; }
    public void setFilesChanged(int filesChanged) { this.filesChanged = filesChanged; }

    public List<String> getFileList() { return fileList; }
    public void setFileList(List<String> fileList) { this.fileList = fileList; }

    public boolean isAutoCommit() { return isAutoCommit; }
    public void setAutoCommit(boolean autoCommit) { isAutoCommit = autoCommit; }

    @Override
    public String toString() {
        return shortHash + " - " + message + " (" + timestamp + ")";
    }
}
