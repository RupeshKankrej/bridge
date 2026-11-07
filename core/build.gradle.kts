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
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    val protocolDir = rootProject.file("tools/protocol")
    inputs.dir(protocolDir)
    systemProperty("bridge.protocolDir", protocolDir.absolutePath)
}
