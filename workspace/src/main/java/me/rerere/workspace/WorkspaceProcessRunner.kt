package me.rerere.workspace

import java.io.File

/**
 * Starts long-lived processes inside a workspace PRoot environment.
 *
 * Unlike [WorkspaceShellRunner.execute] (one-shot: run a command, collect output, exit),
 * this runner launches a process that stays alive so the caller can interact with it over
 * stdin/stdout — required for ACP agents and other interactive CLIs.
 *
 * @param baseDir parent directory holding all workspaces (`<filesDir>/workspaces`).
 * @param bindMounts Android-local dirs mounted into the rootfs (same table as the app uses
 *   for the one-shot runner, so behavior stays consistent).
 */
class WorkspaceProcessRunner(
    private val baseDir: File,
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
    private val nativeLibraryDir: File? = null,
) {
    /**
     * Starts [command] inside the PRoot container of [root] and returns a session whose
     * stdin/stdout are wired to the child process.
     *
     * @param command argv of the program to run inside the container (e.g. `npx`, `node`, …).
     * @param cwd workspace-relative working directory (empty string = workspace root).
     * @param extraBindMounts additional bind mounts layered on top of the default table.
     */
    fun start(
        root: String,
        command: List<String>,
        cwd: String = "",
        extraBindMounts: List<WorkspaceBindMount> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
    ): WorkspaceProcessSession {
        require(root.matches(ROOT_NAME_REGEX)) { "Invalid workspace root name: $root" }
        require(command.isNotEmpty()) { "Command is required" }

        val workspaceDir = File(baseDir, root)
        val filesDir = File(workspaceDir, FILES_DIR)
        val linuxDir = File(workspaceDir, LINUX_DIR)
        val tempDir = File(workspaceDir, TEMP_DIR)

        require(linuxDir.isDirectory && File(linuxDir, "bin/sh").isFile) {
            "Rootfs is not installed for workspace: $root"
        }
        require(filesDir.isDirectory) { "Workspace files dir does not exist: $root" }

        val proot = nativeLibraryDir?.let { File(it, PROOT_EXEC) }
        val loader = nativeLibraryDir?.let { File(it, PROOT_LOADER) }
        if (proot != null) require(proot.isFile) { "proot executable not found: ${proot.absolutePath}" }
        if (loader != null) require(loader.isFile) { "proot loader not found: ${loader.absolutePath}" }

        tempDir.mkdirs()
        RootfsPatcher().patch(linuxDir)

        val process = if (proot != null) {
            ProcessBuilder(prootCommand(proot, linuxDir, filesDir, tempDir, cwd, command, extraBindMounts, extraEnv))
                .directory(filesDir)
                .redirectErrorStream(false)
                .apply {
                    environment()["PROOT_LOADER"] = loader!!.absolutePath
                    environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
                    environment()["TMPDIR"] = tempDir.absolutePath
                }
                .start()
        } else {
            // 无 proot 环境（JVM 单元测试 / 非 Android 运行）：直接运行命令，便于 mock 测试
            ProcessBuilder(command)
                .directory(filesDir)
                .redirectErrorStream(false)
                .start()
        }
        return WorkspaceProcessSession(process)
    }

    private fun prootCommand(
        proot: File,
        linuxDir: File,
        filesDir: File,
        tempDir: File,
        cwd: String,
        command: List<String>,
        extraBindMounts: List<WorkspaceBindMount>,
        extraEnv: Map<String, String>,
    ): List<String> {
        val normalizedCwd = cwd.trim().trim('/')
        val prootCwd = if (normalizedCwd.isBlank()) {
            WorkspaceManager.ROOTFS_WORKSPACE_DIR
        } else {
            "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$normalizedCwd"
        }

        val argv = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            prootCwd,
            "-b",
            "${filesDir.absolutePath}:${WorkspaceManager.ROOTFS_WORKSPACE_DIR}",
        )

        argv += buildBindMountArgs(bindMounts)
        argv += buildBindMountArgs(extraBindMounts)

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                argv += "-b"
                argv += path
            }
        }

        // Drop host environment and exec the command directly (not via bash -c), so the
        // child owns stdin/stdout and stays alive until it decides to exit.
        argv += "/usr/bin/env"
        argv += "-i"
        argv += "HOME=/root"
        argv += "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        argv += "TERM=xterm-256color"
        argv += "LANG=C.UTF-8"
        argv += "LC_ALL=C.UTF-8"
        extraEnv.forEach { (key, value) ->
            argv += "$key=$value"
        }
        argv += command
        return argv
    }

    private companion object {
        const val PROOT_EXEC = "libproot_exec.so"
        const val PROOT_LOADER = "libproot_loader.so"
        const val FILES_DIR = "files"
        const val LINUX_DIR = "linux"
        const val TEMP_DIR = "tmp"
        val ROOT_NAME_REGEX = Regex("[a-zA-Z0-9_-]+")
    }
}
