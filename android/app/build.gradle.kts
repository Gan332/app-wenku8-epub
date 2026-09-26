import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "com.example.hyperreader"
    // MiuiX 0.9.4 的 AAR 要求 compileSdk 37+；targetSdk 保持 36，不引入运行时行为变更
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.hyperreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.8.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // CI 日志默认只打印异常类型，断言信息全丢，远程无法定位。
    // 打开后 --log-failed 能直接给出 expected/actual。
    testOptions {
        unitTests.all {
            it.testLogging.showExceptions = true
            it.testLogging.showStackTraces = true
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // 签名配置全部来自环境变量（CI 由 GitHub Secrets 注入），
    // 密钥与口令绝不进入版本库（AGENTS.md §10）。
    signingConfigs {
        create("release") {
            val store = System.getenv("HYPERREADER_KEYSTORE")
            val storePassword = System.getenv("HYPERREADER_STORE_PASSWORD")
            val keyAlias = System.getenv("HYPERREADER_KEY_ALIAS")
            val keyPassword = System.getenv("HYPERREADER_KEY_PASSWORD")
            if (!store.isNullOrBlank() && !storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
                storeFile = file(store)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-optimize.txt"), "proguard-rules.pro")
            // 缺 Secrets 时不静默降级成 debug 签名，避免产出「看起来正常」的假包
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.core)
    implementation(libs.miuix.icons)
    implementation(libs.compose.material)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
}
