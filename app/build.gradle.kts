plugins { id("com.android.application") }

dependencyLocking { lockAllConfigurations() }

android {
    namespace = "com.dshmobile.shell"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "com.dshmobile.shell"
        minSdk = 29
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0-preview.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("distribution") {
            storeType = "PKCS12"
            System.getenv("DSH_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = System.getenv("DSH_STORE_PASSWORD")
            keyAlias = System.getenv("DSH_KEY_ALIAS")
            keyPassword = System.getenv("DSH_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("distribution")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    flavorDimensions += "architecture"
    productFlavors {
        create("arm64") { dimension = "architecture"; ndk { abiFilters += "arm64-v8a" } }
        create("x64") { dimension = "architecture"; ndk { abiFilters += "x86_64" } }
    }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libdsh_*.so" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; checkReleaseBuilds = true; warningsAsErrors = false }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

val verifyRuntime = tasks.register("verifyRuntime") {
    doLast {
        check(file("src/main/assets/runtime.zip").isFile) { "Run scripts/build-runtime.sh before packaging." }
        check(file("src/main/assets/runtime-manifest.json").isFile) { "Missing verified runtime manifest." }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(verifyRuntime) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    doFirst {
        val abi = if (name.contains("Arm64")) "arm64-v8a" else if (name.contains("X64")) "x86_64" else return@doFirst
        listOf("node", "bash", "git", "git_remote_http", "rg", "curl", "pty", "flock", "posix", "pnpm", "npm", "npx").forEach {
            check(file("src/main/jniLibs/$abi/libdsh_$it.so").isFile) { "Missing native runtime component for $abi: $it" }
        }
        check(file("src/main/jniLibs/$abi/libc++_shared.so").isFile) { "Missing C++ runtime for $abi" }
    }
}
tasks.matching { it.name.startsWith("validateSigning") && it.name.endsWith("Release") }.configureEach {
    doFirst {
        listOf("DSH_KEYSTORE", "DSH_STORE_PASSWORD", "DSH_KEY_ALIAS", "DSH_KEY_PASSWORD").forEach {
            check(!System.getenv(it).isNullOrBlank()) { "Missing release signing setting: $it" }
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.11.0")
    implementation("androidx.webkit:webkit:1.14.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
