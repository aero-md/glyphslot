import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.aero.glyphslot"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aero.glyphslot"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
}

// GlyphMatrixSDK.aar : téléchargé au premier build, non versionné (.gitignore)
val glyphSdkAar = layout.projectDirectory.file("libs/GlyphMatrixSDK.aar").asFile
val downloadGlyphSdk = tasks.register("downloadGlyphSdk") {
    description = "Télécharge GlyphMatrixSDK.aar depuis le GlyphMatrix-Developer-Kit de Nothing"
    outputs.file(glyphSdkAar)
    onlyIf { !glyphSdkAar.exists() }
    doLast {
        glyphSdkAar.parentFile.mkdirs()
        val url = URI(
            "https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit/raw/main/GlyphMatrixSDK.aar"
        ).toURL()
        url.openStream().use { input ->
            glyphSdkAar.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadGlyphSdk) }

dependencies {
    implementation(files("libs/GlyphMatrixSDK.aar"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
