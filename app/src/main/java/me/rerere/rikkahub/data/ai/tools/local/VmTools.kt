package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.androidvm.BlackBoxHost
import me.rerere.androidvm.VirtualEngine
import me.rerere.androidvm.VmEngineType
import me.rerere.androidvm.VmInstance
import me.rerere.androidvm.VmModuleInfo
import me.rerere.androidvm.VmRepository
import me.rerere.androidvm.engine.BlackBoxEngine
import me.rerere.androidvm.engine.GuestRomEngine
import me.rerere.androidvm.engine.LinuxContainerEngine
import java.io.File

/**
 * 虚拟机工具组：让 AI 完全控制应用内虚拟机（VM）实例。
 *
 * 覆盖链路：
 * 1. 枚举实例（vm_list_instances）——引擎类型/系统版本/已装应用
 * 2. 应用生命周期（vm_install_app / vm_list_apps / vm_launch_app / vm_uninstall_app）
 *    - Android 引擎（BlackBox）：安装本地 APK 文件或克隆宿主已装应用，启动/卸载按 userId 空间隔离
 *    - Linux 引擎：软件包安装走 apt（见 WorkspaceTools），此处仅查询
 * 3. Xposed 模块管理（vm_manage_modules）——列出/安装/启停/卸载（BlackBox 引擎专属）
 *
 * 所有写操作（安装/卸载/启动/模块变更）needsApproval=true，与 Termux 工具的安全策略一致。
 */
internal fun buildVmTools(context: Context): List<Tool> = listOf(
    buildVmListInstancesTool(context),
    buildVmInstallAppTool(context),
    buildVmListAppsTool(context),
    buildVmLaunchAppTool(context),
    buildVmUninstallAppTool(context),
    buildVmManageModulesTool(context),
)

private fun engineFor(context: Context, instance: VmInstance): VirtualEngine =
    when (instance.engineType) {
        VmEngineType.LINUX -> LinuxContainerEngine(context)
        VmEngineType.ANDROID -> BlackBoxEngine()
        VmEngineType.GUEST_ROM -> GuestRomEngine(context)
    }

private suspend fun loadInstances(context: Context): List<VmInstance> =
    VmRepository(context).load()

/** 安装/卸载后回写实例的 installedApps，保证 UI 与 AI 看到一致状态 */
private suspend fun updateInstanceApps(
    context: Context,
    instanceId: String,
    apps: List<String>,
) {
    val repo = VmRepository(context)
    val all = repo.load()
    val idx = all.indexOfFirst { it.id == instanceId }
    if (idx >= 0) {
        repo.save(all.toMutableList().also { it[idx] = it[idx].copy(installedApps = apps) })
    }
}

private fun engineHint(instance: VmInstance): String = when {
    instance.engineType == VmEngineType.ANDROID && !BlackBoxHost.isAvailable() ->
        "Android virtualization engine (BlackBox) is not compiled into this build " +
            "(requires blackbox.enable=true). Install/launch will fail until then."
    else -> ""
}

private fun engineUnavailableHint(): String =
    "Android virtualization engine (BlackBox) is not compiled into this build. " +
        "Rebuild with blackbox.enable=true to enable APK install/launch."

private fun buildVmListInstancesTool(context: Context): Tool = Tool(
    name = "vm_list_instances",
    description = "List all virtual machine (VM) instances in the app with their engine type " +
        "(android = app virtualization via BlackBox, linux = proot container, guest_rom = guest ROM), " +
        "system label, anti-detection flags and installed apps. Run this first to discover instance IDs " +
        "before calling other vm_* tools.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { }, required = emptyList())
    },
    needsApproval = { false },
    execute = {
        withContext(Dispatchers.IO) {
            val instances = loadInstances(context)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("count", instances.size)
                        put(
                            "instances",
                            kotlinx.serialization.json.buildJsonArray {
                                instances.forEach { vm ->
                                    add(
                                        buildJsonObject {
                                            put("id", vm.id)
                                            put("name", vm.name)
                                            put("engine", vm.engineType.name.lowercase())
                                            put("system", vm.systemLabel)
                                            put("installed_apps", kotlinx.serialization.json.buildJsonArray {
                                                vm.installedApps.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                                            })
                                            val hint = engineHint(vm)
                                            if (hint.isNotBlank()) put("hint", hint)
                                        },
                                    )
                                }
                            },
                        )
                        if (instances.isEmpty()) {
                            put("hint", "No VM instances yet. Create one in the app: Virtual Machines page.")
                        }
                    }.toString(),
                ),
            )
        }
    },
)

private fun buildVmInstallAppTool(context: Context): Tool = Tool(
    name = "vm_install_app",
    description = "Install an app into a virtual machine instance (Android engine). " +
        "Two sources are supported: (a) an absolute path to a local .apk file accessible to the app " +
        "(e.g. a file inside the app sandbox workspace or cache), or (b) the package name of an app " +
        "already installed on the host device, which is cloned into the VM as a whole. " +
        "Returns the installed package name. Requires the BlackBox engine (blackbox.enable=true).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("instance_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Target VM instance id (from vm_list_instances)")
                })
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to a .apk file, or a host-installed package name to clone")
                })
            },
            required = listOf("instance_id", "source"),
        )
    },
    needsApproval = { true },
    execute = { input ->
        val params = input.jsonObject
        val instanceId = params.string("instance_id").orEmpty()
        val source = params.string("source").orEmpty()
        require(instanceId.isNotBlank()) { "instance_id is required" }
        require(source.isNotBlank()) { "source is required" }
        withContext(Dispatchers.IO) {
            val instance = loadInstances(context).firstOrNull { it.id == instanceId }
                ?: error("VM instance not found: $instanceId (run vm_list_instances)")
            if (instance.engineType != VmEngineType.ANDROID) {
                error("Instance ${instance.name} uses the ${instance.engineType.name.lowercase()} engine; vm_install_app only supports the android engine")
            }
            if (!BlackBoxHost.isAvailable()) error(engineUnavailableHint())
            if (!source.startsWith("/") || !File(source).isFile) {
                // 非本地文件路径 → 视为宿主包名克隆
                runCatching { context.packageManager.getPackageInfo(source, 0) }
                    .onFailure { error("source is neither an existing apk file nor a host-installed package: $source") }
            }
            val engine = engineFor(context, instance)
            val pkg = engine.installApp(instance, source)
            val apps = engine.listApps(instance)
            updateInstanceApps(context, instanceId, apps)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("installed_package", pkg)
                        put("instance", instance.name)
                        put("installed_apps_count", apps.size)
                    }.toString(),
                ),
            )
        }
    },
)

private fun buildVmListAppsTool(context: Context): Tool = Tool(
    name = "vm_list_apps",
    description = "List apps installed inside a virtual machine instance. " +
        "For android instances this queries the BlackBox user space of that instance; " +
        "for linux instances it lists provisioned packages.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("instance_id", buildJsonObject {
                    put("type", "string")
                    put("description", "VM instance id (from vm_list_instances)")
                })
            },
            required = listOf("instance_id"),
        )
    },
    needsApproval = { false },
    execute = { input ->
        val params = input.jsonObject
        val instanceId = params.string("instance_id").orEmpty()
        require(instanceId.isNotBlank()) { "instance_id is required" }
        withContext(Dispatchers.IO) {
            val instance = loadInstances(context).firstOrNull { it.id == instanceId }
                ?: error("VM instance not found: $instanceId (run vm_list_instances)")
            val apps = engineFor(context, instance).listApps(instance)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("instance", instance.name)
                        put("engine", instance.engineType.name.lowercase())
                        put("count", apps.size)
                        put("apps", kotlinx.serialization.json.buildJsonArray {
                            apps.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        })
                        val hint = engineHint(instance)
                        if (hint.isNotBlank()) put("hint", hint)
                    }.toString(),
                ),
            )
        }
    },
)

private fun buildVmLaunchAppTool(context: Context): Tool = Tool(
    name = "vm_launch_app",
    description = "Launch an installed app inside a virtual machine instance (Android engine). " +
        "The app runs inside the isolated virtual user space of that instance.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("instance_id", buildJsonObject {
                    put("type", "string")
                    put("description", "VM instance id (from vm_list_instances)")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Package name of the installed app (from vm_list_apps)")
                })
            },
            required = listOf("instance_id", "package_name"),
        )
    },
    needsApproval = { true },
    execute = { input ->
        val params = input.jsonObject
        val instanceId = params.string("instance_id").orEmpty()
        val packageName = params.string("package_name").orEmpty()
        require(instanceId.isNotBlank()) { "instance_id is required" }
        require(packageName.isNotBlank()) { "package_name is required" }
        withContext(Dispatchers.IO) {
            val instance = loadInstances(context).firstOrNull { it.id == instanceId }
                ?: error("VM instance not found: $instanceId (run vm_list_instances)")
            if (instance.engineType != VmEngineType.ANDROID) {
                error("Instance ${instance.name} uses the ${instance.engineType.name.lowercase()} engine; vm_launch_app only supports the android engine")
            }
            if (!BlackBoxHost.isAvailable()) error(engineUnavailableHint())
            engineFor(context, instance).launch(instance, packageName)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("launched", packageName)
                        put("instance", instance.name)
                    }.toString(),
                ),
            )
        }
    },
)

private fun buildVmUninstallAppTool(context: Context): Tool = Tool(
    name = "vm_uninstall_app",
    description = "Uninstall an app from a virtual machine instance (Android engine). " +
        "Only removes it from the VM's isolated user space; the host install is untouched.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("instance_id", buildJsonObject {
                    put("type", "string")
                    put("description", "VM instance id (from vm_list_instances)")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Package name to remove (from vm_list_apps)")
                })
            },
            required = listOf("instance_id", "package_name"),
        )
    },
    needsApproval = { true },
    execute = { input ->
        val params = input.jsonObject
        val instanceId = params.string("instance_id").orEmpty()
        val packageName = params.string("package_name").orEmpty()
        require(instanceId.isNotBlank()) { "instance_id is required" }
        require(packageName.isNotBlank()) { "package_name is required" }
        withContext(Dispatchers.IO) {
            val instance = loadInstances(context).firstOrNull { it.id == instanceId }
                ?: error("VM instance not found: $instanceId (run vm_list_instances)")
            if (instance.engineType != VmEngineType.ANDROID) {
                error("Instance ${instance.name} uses the ${instance.engineType.name.lowercase()} engine; vm_uninstall_app only supports the android engine")
            }
            if (!BlackBoxHost.isAvailable()) error(engineUnavailableHint())
            val engine = engineFor(context, instance)
            engine.uninstallApp(instance, packageName)
            val apps = engine.listApps(instance)
            updateInstanceApps(context, instanceId, apps)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("uninstalled", packageName)
                        put("installed_apps_count", apps.size)
                    }.toString(),
                ),
            )
        }
    },
)

private fun buildVmManageModulesTool(context: Context): Tool = Tool(
    name = "vm_manage_modules",
    description = "Manage Xposed modules of the Android virtualization engine (BlackBox). " +
        "Actions: list (all installed modules with enabled state), install (load a local .apk " +
        "Xposed module file into the engine), enable/disable (by module package name), " +
        "uninstall (by module package name). Modules hook apps running inside VM instances.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.buildJsonArray {
                        listOf("list", "install", "enable", "disable", "uninstall").forEach {
                            add(kotlinx.serialization.json.JsonPrimitive(it))
                        }
                    })
                    put("description", "Operation to perform")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to the Xposed module .apk file (action=install)")
                })
                put("module_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Module package name (action=enable/disable/uninstall)")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { true },
    execute = { input ->
        val params = input.jsonObject
        val action = params.string("action").orEmpty()
        require(action.isNotBlank()) { "action is required" }
        withContext(Dispatchers.IO) {
            if (!BlackBoxHost.isAvailable()) error(engineUnavailableHint())
            val engine = BlackBoxEngine()
            when (action) {
                "list" -> {
                    val modules = engine.listModules()
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("count", modules.size)
                                put("modules", kotlinx.serialization.json.buildJsonArray {
                                    modules.forEach { add(moduleJson(it)) }
                                })
                                if (modules.isEmpty()) {
                                    put("hint", "No Xposed modules installed. Use action=install with a module apk path.")
                                }
                            }.toString(),
                        ),
                    )
                }
                "install" -> {
                    val path = params.string("path").orEmpty()
                    require(path.isNotBlank()) { "path is required for action=install" }
                    require(File(path).isFile) { "module apk not found: $path" }
                    val pkg = engine.installModule(VmInstance(id = "modules", name = "modules", engineType = VmEngineType.ANDROID, systemLabel = "", rootfsUrl = ""), path)
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("installed_module", pkg)
                            }.toString(),
                        ),
                    )
                }
                "enable", "disable" -> {
                    val moduleId = params.string("module_id").orEmpty()
                    require(moduleId.isNotBlank()) { "module_id is required for action=$action" }
                    engine.setModuleEnabled(moduleId, action == "enable")
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("module", moduleId)
                                put("enabled", action == "enable")
                            }.toString(),
                        ),
                    )
                }
                "uninstall" -> {
                    val moduleId = params.string("module_id").orEmpty()
                    require(moduleId.isNotBlank()) { "module_id is required for action=uninstall" }
                    engine.uninstallModule(moduleId)
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("uninstalled_module", moduleId)
                            }.toString(),
                        ),
                    )
                }
                else -> error("Unknown action: $action (expected list/install/enable/disable/uninstall)")
            }
        }
    },
)

private fun moduleJson(m: VmModuleInfo): JsonObject = buildJsonObject {
    put("module_id", m.moduleId)
    put("name", m.name)
    put("kind", m.kind.name.lowercase())
    put("enabled", m.enabled)
    if (m.description.isNotBlank()) put("description", m.description)
}

private fun JsonObject.string(name: String): String? =
    runCatching { get(name)?.jsonPrimitive?.contentOrNull }.getOrNull()
