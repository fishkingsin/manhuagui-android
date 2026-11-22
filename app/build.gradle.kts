import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.mozilla.rust-android-gradle.rust-android") version "0.10.0"
}

android {
    namespace = "com.fishkingsin.manhuagui"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.fishkingsin.manhuagui"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/source/uniffi"))
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

cargo {
    module = "../ffi"
    libname = "manhuagui_ffi"
    targets = listOf("arm64", "x86_64")
    profile = "debug"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation("net.java.dev.jna:jna:5.12.0@aar")
}

val hostFfiLibrary = rootProject.file(
    "ffi/target/debug/libmanhuagui_ffi" +
        if (System.getProperty("os.name").startsWith("Mac")) ".dylib" else ".so",
)
val uniffiSources = layout.buildDirectory.dir("generated/source/uniffi")

val buildHostFfi = tasks.register<Exec>("buildHostFfi") {
    workingDir(rootProject.projectDir)
    commandLine("cargo", "build", "--manifest-path", "ffi/Cargo.toml", "--lib")
    inputs.files(
        rootProject.file("ffi/Cargo.toml"),
        rootProject.file("ffi/Cargo.lock"),
        rootProject.file("ffi/uniffi-bindgen.rs"),
        fileTree(rootProject.file("ffi/src")),
    )
    outputs.file(hostFfiLibrary)
}

val generateUniffiBindings = tasks.register<Exec>("generateUniffiBindings") {
    dependsOn(buildHostFfi)
    workingDir(rootProject.file("ffi"))
    commandLine(
        "cargo", "run", "--manifest-path", "Cargo.toml",
        "--features", "uniffi-bindgen", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", hostFfiLibrary.absolutePath,
        "--language", "kotlin", "--out-dir", uniffiSources.get().asFile.absolutePath,
    )
    inputs.file(hostFfiLibrary)
    outputs.dir(uniffiSources)
}

tasks.named("preBuild") {
    dependsOn(generateUniffiBindings, "cargoBuild")
}
