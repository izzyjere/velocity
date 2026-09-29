plugins {
    id("com.android.application")
    id("androidx.room")
}

room { schemaDirectory("$projectDir/schemas") }

val configuredVersionName = providers.gradleProperty("versionName").orElse("1.2.0")
val configuredVersionCode = providers.gradleProperty("versionCode").map(String::toInt).orElse(1_002_000)
val releaseStoreFile = System.getenv("VELOCITY_SIGNING_STORE_FILE")
val releaseStorePassword = System.getenv("VELOCITY_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("VELOCITY_SIGNING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("VELOCITY_SIGNING_KEY_PASSWORD")
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }

if (releaseSigningValues.any { !it.isNullOrBlank() } && !hasReleaseSigning) {
    throw GradleException("Release signing is only partially configured; all VELOCITY_SIGNING_* values are required")
}

android {
    namespace = "zm.co.codelabs.adm"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "zm.co.codelabs.adm"
        minSdk = 26
        targetSdk = 36
        versionCode = configuredVersionCode.get()
        versionName = configuredVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("androidTest").assets.setSrcDirs(listOf("$projectDir/schemas"))
}

tasks.register("verifyReleaseSigningConfiguration") {
    doLast {
        check(hasReleaseSigning) { "Release signing is not configured. Set all VELOCITY_SIGNING_* environment variables." }
        check(file(releaseStoreFile!!).isFile) { "Release keystore does not exist: $releaseStoreFile" }
    }
}

dependencies {
    // Keep the serialization runtime aligned with Room 2.8.x's generated
    // migration-schema serializers. Lifecycle otherwise selects 1.7.3 while
    // room-migration brings JSON 1.8.1, which fails on-device with an
    // AbstractMethodError before a migration can be exercised.
    implementation(platform("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.8.1"))
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-livedata:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("androidx.room:room-runtime:2.8.5")
    annotationProcessor("androidx.room:room-compiler:2.8.5")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.chromium.net:cronet-embedded:500.0.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
}
