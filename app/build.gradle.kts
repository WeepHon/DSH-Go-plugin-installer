plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dshgo.plugininstaller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dshgo.plugininstaller"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "1.1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packagingOptions {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
                "META-INF/*.version"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.documentfile:documentfile:1.0.0")
    // 离线缓存里只有 1.7.3；appcompat 传递依赖的 1.6.4 未缓存，显式提升避免离线构建失败
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
        // 离线缓存只有这些版本的 jar；Kotlin 1.9.22 的 stdlib 与 1.9.10 的 jdk7/jdk8 兼容
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-common:1.9.22")
    }
}
