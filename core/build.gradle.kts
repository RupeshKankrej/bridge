import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)

    jvm()
    android {
        namespace = "bridge.core"
        compileSdk = 37
        minSdk = 29
        withHostTest {}
    }
    val xcf = XCFramework("BridgeCore")
    macosArm64 {
        binaries.framework {
            baseName = "BridgeCore"
            // Static: linked into the app binary, so Xcode needs no embed step.
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.named<Test>("jvmTest") {
    val protocolDir = rootProject.file("tools/protocol")
    inputs.dir(protocolDir)
    systemProperty("bridge.protocolDir", protocolDir.absolutePath)
}
