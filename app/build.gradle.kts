import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.storm.shijuan"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.storm.shijuan"
        minSdk = 24
        targetSdk = 35
        versionCode = 68
        versionName = "3.04"
        resourceConfigurations += listOf("zh", "zh-rCN")
    }

    signingConfigs {
        create("release") {
            val localProps = Properties().apply {
                val lpFile = rootProject.file("local.properties")
                if (lpFile.exists()) {
                    lpFile.inputStream().use { stream -> this.load(stream) }
                }
            }
            val keyPath = System.getenv("KEYSTORE_PATH")
                ?: project.findProperty("KEYSTORE_PATH")?.toString()
                ?: localProps.getProperty("KEYSTORE_PATH")
                ?: "../keystore/shijuan.jks"
            val storePass = System.getenv("KEYSTORE_PASSWORD")
                ?: project.findProperty("KEYSTORE_PASSWORD")?.toString()
                ?: localProps.getProperty("KEYSTORE_PASSWORD")
                ?: (if (file(keyPath).exists()) "shijuan" else null)
            val keyAliasName = System.getenv("KEY_ALIAS")
                ?: project.findProperty("KEY_ALIAS")?.toString()
                ?: localProps.getProperty("KEY_ALIAS")
                ?: "shijuan"
            val keyPass = System.getenv("KEY_PASSWORD")
                ?: project.findProperty("KEY_PASSWORD")?.toString()
                ?: localProps.getProperty("KEY_PASSWORD")
                ?: storePass

            val kFile = file(keyPath)
            if (kFile.exists() && !storePass.isNullOrBlank()) {
                storeFile = kFile
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            val relSigning = signingConfigs.getByName("release")
            if (relSigning.storeFile != null && relSigning.storeFile!!.exists()) {
                signingConfig = relSigning
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // 使用标准 Android Debug 签名，避免 Debug 与 Release 密钥混用
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // 单元测试跑在 JVM 上，Android 自带的 org.json 是空壳，这里补一份真实现
    testImplementation("org.json:json:20240303")
}
