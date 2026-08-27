plugins {
    id("rikkahub.android.library.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.androidvm"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    // 客机 ROM 原生引擎（路线 B）：默认关闭，避免沙盒无 NDK 时构建失败。
    // 真机启用：在 gradle.properties 设 guestrom.native.enable=true（需安装 NDK）。
    if (providers.gradleProperty("guestrom.native.enable").getOrElse("false").toBoolean()) {
        externalNativeBuild {
            ndkBuild { path("jni/Android.mk") }
        }
    }
}

dependencies {
    implementation(project(":workspace"))
    implementation(project(":common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.material3)
    implementation(libs.huge.icons)
    implementation(libs.lucide.icons)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
}
