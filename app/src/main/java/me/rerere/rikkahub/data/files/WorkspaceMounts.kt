package me.rerere.rikkahub.data.files

import android.content.Context
import android.os.Environment
import android.util.Log
import me.rerere.workspace.WorkspaceBindMount
import java.io.File

object WorkspaceMounts {
    private const val TAG = "WorkspaceMounts"

    fun androidLocalMounts(context: Context): List<WorkspaceBindMount> = buildList {
        add(WorkspaceBindMount(File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }, "/skills"))
        add(WorkspaceBindMount(File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }, "/tool_outputs"))
        add(WorkspaceBindMount(File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() }, "/upload"))
        add(WorkspaceBindMount(File(context.filesDir, FileFolders.TOOLS).apply { mkdirs() }, "/tools"))
        add(
            WorkspaceBindMount(
                File(context.filesDir, FileFolders.CONFIG)
                    .apply { mkdirs(); ensureConfigReadme(this) },
                "/config",
            )
        )
        val sdcard = Environment.getExternalStorageDirectory()
        if (sdcard != null) {
            add(WorkspaceBindMount(sdcard, "/sdcard"))
        } else {
            Log.w(TAG, "External storage not available, skipping /sdcard mount")
        }
    }

    /** /config 扩展点说明文档: 首次创建时写入, 让用户与 AI 能发现全部自定义能力 */
    private fun ensureConfigReadme(configDir: File) {
        val readme = File(configDir, "README.md")
        if (readme.isFile) return
        runCatching {
            readme.writeText(
                """
                # /config 工作区配置区

                此目录挂载到容器内 `/config`, 内容跨工作区、跨设备共享(rootfs 重装不丢失)。

                ## 可用扩展点

                | 文件/目录 | 作用 | 加载时机 |
                |-----------|------|----------|
                | `profile.d/*.sh` | 自定义 shell 环境(alias/env/函数), 每个脚本独立容错 | 每次 shell 启动 |
                | `env` | 简易环境变量, 每行 `KEY=VALUE`, `#` 注释 | 每次 shell 启动 |
                | `npm-user.npmrc` | npm 配置覆盖(优先级高于 ~/.npmrc) | shell 内 npm 命令 |
                | `npm-global/` | npm 全局包装目录(`npm i -g --prefix`), 自动加入 PATH | 每次 shell 启动 |
                | `gitconfig` | git 配置追加(身份/代理/别名), 通过 [include] 合并 | git 命令 |
                | `ssh/id_ed25519`(或 id_rsa) | git SSH 密钥, 自动生成 GIT_SSH_COMMAND | git over SSH |
                | `ssh/known_hosts`, `ssh/ssh_config` | SSH 已知主机与客户端配置(可选) | git over SSH |
                | `pip.conf` | pip 镜像/配置(通过 PIP_CONFIG_FILE) | shell 内 pip 命令 |
                | `tools.txt` | 额外系统包清单, 每行一个包名, `#` 注释 | Agent 安装工具链时 |

                ## 示例

                ```bash
                # profile.d/10-mine.sh
                export EDITOR=vim
                alias gs='git status'
                ```

                ```
                # env
                HTTP_PROXY=http://192.168.1.10:7890
                ```

                ```
                # tools.txt
                jq
                ripgrep
                ```
                """.trimIndent()
            )
        }.onFailure { Log.w(TAG, "Failed to write /config README", it) }
    }
}
