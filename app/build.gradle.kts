plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.evelog.coletor"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    defaultConfig {
        applicationId = "br.com.evelog.coletor"
        minSdk = 23
        targetSdk = 35
        versionCode = 26
        versionName = "1.25"
    }
}

tasks.configureEach {
    if (name == "assembleDebug") {
        doLast {
            val outputDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
            val defaultApk = outputDir.resolve("app-debug.apk")
            val version = android.defaultConfig.versionName ?: "desconhecida"
            defaultApk.copyTo(outputDir.resolve("Coletor-Evelog-v${version}.apk"), overwrite = true)
        }
    }
}
