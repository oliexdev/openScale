import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

hilt {
    enableAggregatingTask = false
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.health.openscale"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.health.openscale"
        minSdk = 31
        targetSdk = 37
        versionCode = 75
        versionName = "3.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appName"] = "openScale"
        manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
        manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("../../openScale.keystore")
            val keystoreProperties = Properties()
            var propertiesLoaded : Boolean

            try {
                FileInputStream(keystorePropertiesFile).use { fis ->
                    keystoreProperties.load(fis)
                }
                propertiesLoaded = true
            } catch (_: FileNotFoundException) {
                project.logger.warn("Keystore properties file not found: ${keystorePropertiesFile.absolutePath}. Release signing might fail if not configured via environment variables.")
                propertiesLoaded = false
            }

            if (propertiesLoaded && keystoreProperties.containsKey("releaseKeyStore")) {
                storeFile = file(rootProject.projectDir.canonicalPath + "/" + keystoreProperties.getProperty("releaseKeyStore"))
                keyAlias = keystoreProperties.getProperty("releaseKeyAlias")
                keyPassword = keystoreProperties.getProperty("releaseKeyPassword")
                storePassword = keystoreProperties.getProperty("releaseStorePassword")
            } else {
                project.logger.warn("Release signing information not fully loaded from properties. Ensure it's set via environment variables or the properties file is correct.")
            }
        }

        create("oss") {
            val keystoreOSSPropertiesFile = rootProject.file("../../openScale_oss.keystore")
            val keystoreOSSProperties = Properties()
            var propertiesLoaded : Boolean

            try {
                FileInputStream(keystoreOSSPropertiesFile).use { fis ->
                    keystoreOSSProperties.load(fis)
                }
                propertiesLoaded = true
            } catch (_: FileNotFoundException) {
                project.logger.warn("OSS Keystore properties file not found: ${keystoreOSSPropertiesFile.absolutePath}. OSS signing might fail if not configured via environment variables.")
                propertiesLoaded = false
            }

            if (propertiesLoaded && keystoreOSSProperties.containsKey("releaseKeyStore")) {
                storeFile = file(rootProject.projectDir.canonicalPath + "/" + keystoreOSSProperties.getProperty("releaseKeyStore"))
                keyAlias = keystoreOSSProperties.getProperty("releaseKeyAlias")
                keyPassword = keystoreOSSProperties.getProperty("releaseKeyPassword")
                storePassword = keystoreOSSProperties.getProperty("releaseStorePassword")
            } else {
                project.logger.warn("OSS signing information not fully loaded from properties. Ensure it's set via environment variables or the properties file is correct.")
            }
        }
    }

    buildTypes {
        configureEach {
            buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
            buildConfigField("String", "BUILD_TIME_UTC", "\"${buildTimeUtc()}\"")
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_dev"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_dev_round"
        }

        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("beta") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            manifestPlaceholders["appName"] = "openScale beta"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_beta"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_beta_round"
        }

        create("oss") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("oss")
            applicationIdSuffix = ".oss"
            versionNameSuffix = "-oss"
            manifestPlaceholders["appName"] = "openScale"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_beta"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_beta_round"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // JVM unit tests touch android.util.Log (via LogManager); return defaults
        // instead of throwing "not mocked" so pure-logic tests can run on the JVM.
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        // Include the version number for every build type except debug.
        val baseName = if (variant.buildType == "debug") {
            "openScale-${variant.buildType}"
        } else {
            "openScale-${android.defaultConfig.versionName}-${variant.buildType}"
        }

        // APK naming. outputFileName is the official replacement for the removed
        // applicationVariants API but still marked @Incubating in AGP 9.
        @Suppress("UnstableApiUsage")
        variant.outputs.forEach { output ->
            output.outputFileName.set("$baseName.apk")
        }

        // AAB naming: the outputs API above only covers APKs, so rename the
        // bundle output once the bundle<Variant> task has produced it.
        val bundleTaskName = "bundle${variant.name.replaceFirstChar { it.uppercase() }}"
        tasks.matching { it.name == bundleTaskName }.configureEach {
            doLast {
                val bundleDir = layout.buildDirectory
                    .dir("outputs/bundle/${variant.name}").get().asFile
                bundleDir.listFiles { _, name -> name.endsWith(".aab") }
                    ?.forEach { aab ->
                        val target = File(bundleDir, "$baseName.aab")
                        if (aab != target) {
                            target.delete()
                            aab.renameTo(target)
                        }
                    }
            }
        }
    }
}


dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.worker)
    implementation(libs.androidx.documentfile)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Vico charts
    implementation(libs.compose.charts)
    implementation(libs.compose.charts.m3)

    // Glance
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Compose reorderable
    implementation(libs.compose.reorderable)
    implementation(libs.compose.material.icons.extended)

    // Kotlin-CSV
    implementation(libs.kotlin.csv.jvm)

    // Blessed Kotlin
    implementation(libs.blessed.kotlin)

    // BouncyCastle for AES-CCM decryption (Xiaomi S400 scale)
    implementation(libs.bouncycastle)

    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    // JVM-runnable Android/Room tests (no emulator needed)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
}

fun safeExec(vararg cmd: String): String = try {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    p.inputStream.bufferedReader().use { it.readText() }.trim().ifEmpty { "unknown" }
} catch (_: Exception) { "unknown" }

fun gitSha(): String {
    System.getenv("GIT_SHA")?.takeIf { it.isNotBlank() }?.let { return it }
    return safeExec("git", "rev-parse", "--short", "HEAD")
}

fun buildTimeUtc(): String {
    System.getenv("BUILD_TIME_UTC")?.takeIf { it.isNotBlank() }?.let { return it }
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
}