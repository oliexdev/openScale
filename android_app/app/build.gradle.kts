import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt")
}

android {
    namespace = "com.health.openscale"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.health.openscale"
        minSdk = 31
        targetSdk = 36
        versionCode = 67
        versionName = "3.0"

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
            } catch (e: FileNotFoundException) {
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
            } catch (e: FileNotFoundException) {
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
        release {
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("beta") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("release")
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

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this
            if (output is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                output.outputFileName = "openScale-${variant.buildType.name}.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kapt {
        arguments {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.datastore.preferences)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Vico charts
    implementation(libs.compose.charts)
    implementation(libs.compose.charts.m3)

    // Compose reorderable
    implementation(libs.compose.reorderable)
    implementation(libs.compose.material.icons.extended)

    // Kotlin-CSV
    implementation(libs.kotlin.csv.jvm)

    // Blessed Kotlin
   // implementation(libs.blessed.kotlin)
    implementation(libs.blessed.java)
}