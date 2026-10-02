plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.klin.read"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.klin.read"
        minSdk = 24
        targetSdk = 36
        // versionCode = 版本号去掉点号：1.0.0 -> 100，1.2.3 -> 123。
        // 前提是每一段都不超过 9；出现两位数（如 1.10.0）会与前一段撞号。
        versionCode = 100
        versionName = "1.0.0"
    }

    /**
     * Sign release builds with a local keystore so the shrunk APK can actually be
     * installed.
     *
     * Without this the release output is `-unsigned` and adb refuses it. The
     * keystore is generated once by `keystore/generate.ps1` and is not committed;
     * if it is missing, release builds simply fall back to unsigned rather than
     * failing the whole build.
     */
    signingConfigs {
        create("local") {
            val keystoreFile = rootProject.file("keystore/watch-release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "kkl1nread"
                keyAlias = "watch"
                keyPassword = "kkl1nread"
            }
        }
    }

    buildTypes {
        release {
            /*
             * R8 shrinking matters more here than in a phone build.
             *
             * The app measured ~60 MB of mapped .dex on the watch, almost all of
             * it Compose. Shrinking removes unused Compose and AndroidX classes,
             * which cuts both the APK that has to be sideloaded and the resident
             * footprint afterwards.
             */
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Applied only when the keystore exists; see signingConfigs above.
            val keystoreFile = rootProject.file("keystore/watch-release.jks")
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("local")
            }
        }
        debug {
            // Debug builds stay unminified so stack traces remain readable.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Settings shows the real version via BuildConfig.VERSION_NAME.
        buildConfig = true
    }

    /**
     * Keep only the ABIs a watch actually uses.
     *
     * The target watch is armeabi-v7a (32-bit ARM). Shipping other ABIs bloats
     * the APK and slows the Bluetooth sideload without any benefit.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    testOptions {
        unitTests.all {
            // Run tests in the Gradle process instead of forking a worker JVM.
            // Forked test workers fail to launch in sandboxed shells that cannot
            // create piped stdio; these are pure JVM logic tests, so isolation
            // buys nothing.
            it.setForkEvery(0)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    // Avatar image loading.
    implementation(libs.coil.compose)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation(libs.junit)
}

/**
 * Copies the signed release APK into the project root.
 *
 * The debug output path is buried several folders deep and, more importantly,
 * the debug APK is about ten times larger than the release one -- installing it
 * on the watch reintroduces the slowness this project exists to fix. Exporting
 * the release build makes the right file the easy one to find.
 *
 * Implemented as a doLast copy rather than a Copy task: the Copy task hashes its
 * inputs, and hashing a file Gradle has just written in the same task graph
 * fails.
 */
tasks.register("exportWatchApk") {
    dependsOn("assembleRelease")
    doLast {
        val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val built = dir.listFiles()
            ?.filter { it.name.endsWith(".apk") && it.name.contains("armeabi-v7a") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("No armeabi-v7a release APK found in ${dir.absolutePath}")

        val target = rootProject.layout.projectDirectory.file("KlinReadForWatch.apk").asFile
        built.copyTo(target, overwrite = true)

        val mb = target.length() / 1024.0 / 1024.0
        println("Watch APK exported to ${target.absolutePath} (${"%.2f".format(mb)} MB)")
    }
}

