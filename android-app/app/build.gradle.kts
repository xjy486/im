plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.jitong.im.android"
    compileSdk = 35

    sourceSets {
        getByName("androidTest").assets.srcDir("schemas")
    }

    fun firebaseProperty(name: String): String =
        project.findProperty(name)?.toString().orEmpty()

    defaultConfig {
        applicationId = "com.jitong.im.android"
        minSdk = 26
        targetSdk = 35
        versionCode = project.findProperty("jitongVersionCode")?.toString()?.toInt() ?: 1
        versionName = project.findProperty("jitongVersion")?.toString() ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "BASE_URL",
            "\"${project.findProperty("jitongBaseUrl") ?: "http://10.0.2.2:8080/"}\""
        )
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${firebaseProperty("firebaseApplicationId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseProperty("firebaseApiKey")}\"")
        buildConfigField("String", "FIREBASE_GCM_SENDER_ID", "\"${firebaseProperty("firebaseGcmSenderId")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseProperty("firebaseProjectId")}\"")
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "\"${firebaseProperty("firebaseStorageBucket")}\"")
        buildConfigField(
            "String",
            "INVITE_HOST",
            "\"${project.findProperty("jitongInviteHost") ?: "app.jitong.im"}\""
        )
        manifestPlaceholders["inviteHost"] = project.findProperty("jitongInviteHost") ?: "app.jitong.im"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["allowCleartext"] = "true"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["allowCleartext"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    val releaseKeystore = project.findProperty("releaseKeystore")?.toString()
    if (!releaseKeystore.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = project.findProperty("releaseStorePassword")?.toString()
                keyAlias = project.findProperty("releaseKeyAlias")?.toString()
                keyPassword = project.findProperty("releaseKeyPassword")?.toString()
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

android.compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    sourceSets {
        getByName("main").kotlin.srcDir("../../client-shared/src/main/kotlin")
        getByName("test").kotlin.srcDir("../../client-shared/src/main/kotlin")
    }
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.google.firebase:firebase-messaging:25.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    kapt("androidx.room:room-compiler:2.7.0")
    implementation("androidx.sqlite:sqlite:2.5.0")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
