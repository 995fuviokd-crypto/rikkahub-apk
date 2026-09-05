package me.rerere.androidvm

/**
 * androidvm 与宿主工作区系统的桥接契约。
 *
 * Linux 容器实例复用 workspace 模块的 rootfs/终端/文件管理能力，但实例清单存在
 * androidvm_instances.json，而终端与工作区页面从工作区数据库读取记录——两套存储
 * 必须保持登记同步，否则"打开终端"查不到记录而白屏。
 *
 * 宿主(app 模块)提供实现并经构造注入；androidvm 不反向依赖 app（避免循环依赖）。
 */
interface VmWorkspaceBridge {
    /**
     * 确保工作区数据库中存在 id 对应的登记（幂等）。
     * 已存在直接返回；不存在则创建（重名自动消解后缀），并建立目录骨架。
     * @return 工作区是否就绪（登记成功或已存在）
     */
    suspend fun ensureLinkedWorkspace(id: String, name: String): Boolean

    /** 删除工作区登记与磁盘数据（实例销毁时调用；记录不存在时静默成功） */
    suspend fun deleteLinkedWorkspace(id: String)

    /** rootfs 安装完成后标记 shell 就绪（详情页/工具安装据此解锁） */
    suspend fun markShellReady(id: String)
}
