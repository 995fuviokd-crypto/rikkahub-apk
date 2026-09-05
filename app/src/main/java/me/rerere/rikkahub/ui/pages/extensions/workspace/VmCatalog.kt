package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.rikkahub.R
import androidx.annotation.StringRes

/**
 * 虚拟机系统镜像目录（ROM 市场式清单）。
 *
 * 所有 URL 均经过可达性验证；下载失败时 RootfsInstaller 会自动走镜像回退。
 */
data class VmImage(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    val url: String,
    val sizeHintMb: Int,
)

object VmCatalog {
    val images: List<VmImage> = listOf(
        VmImage(
            id = "ubuntu-24.04",
            labelRes = R.string.vm_image_ubuntu_2404,
            descRes = R.string.vm_image_ubuntu_2404_desc,
            url = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            sizeHintMb = 35,
        ),
        VmImage(
            id = "ubuntu-25.10",
            labelRes = R.string.vm_image_ubuntu_2510,
            descRes = R.string.vm_image_ubuntu_2510_desc,
            url = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/25.10/release/ubuntu-base-25.10-base-arm64.tar.gz",
            sizeHintMb = 36,
        ),
        VmImage(
            id = "alpine-3.22",
            labelRes = R.string.vm_image_alpine,
            descRes = R.string.vm_image_alpine_desc,
            url = "https://mirrors.aliyun.com/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.0-aarch64.tar.gz",
            sizeHintMb = 3,
        ),
    )

    fun default(): VmImage = images.first()
}
