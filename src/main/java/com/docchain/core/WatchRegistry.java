package com.docchain.core;

import java.nio.file.Path;
import java.util.List;

/**
 * 监听目录注册表 — 主面板等 UI 组件与多目录编排之间的契约。
 *
 * <p>把接口独立出来的目的只有一个：UI 不需要知道背后有几个 Git 仓库、
 * 列表存在哪个文件里，只管"问有哪些目录、加一个、减一个、拿某个目录的版本库"。</p>
 */
public interface WatchRegistry {

    /** 当前全部监听目录（含暂时不可用的，如未插上的移动硬盘）。顺序即展示顺序。 */
    List<Path> getWatchedDirectories();

    /**
     * 获取某个监听目录的版本库；目录未被监听或暂不可用时返回 {@code null}。
     */
    GitRepositoryManager getRepo(Path dir);

    /**
     * 添加一个监听目录：注册文件监听、初始化版本库、持久化列表，
     * 并在后台对目录内已有文件做一次"初始存档"（作为回滚的基线）。
     *
     * @return 规范化后的目录路径
     * @throws IllegalArgumentException 目录不存在、重复、或与已监听目录嵌套
     */
    Path addWatchDirectory(Path dir) throws Exception;

    /**
     * 移除监听目录：停止监听、关闭版本库、持久化列表。
     * 已生成的 {@code .docchain-git} 历史保留在磁盘上，重新添加即可恢复。
     */
    void removeWatchDirectory(Path dir);
}
