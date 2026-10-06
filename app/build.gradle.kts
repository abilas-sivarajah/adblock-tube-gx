import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Signierschlüssel: Standard ist signing/gxtube.jks aus dem Repo, damit jede neue APK
// über die alte installiert werden kann (Abos/Verlauf bleiben erhalten).
// Eigener Schlüssel: Umgebungsvariablen GXTUBE_KEYSTORE / _PASSWORD / _ALIAS / _KEY_PASSWORD setzen.
val keystoreFile = System.getenv("GXTUBE_KEYSTORE")?.takeIf { it.isNotBlank() }
    ?.let { file(it) } ?: rootProject.file("signing/gxtube.jks")
val keystorePassword = System.getenv("GXTUBE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "gxtube-public"
val keyAliasName = System.getenv("GXTUBE_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "gxtube"
val keyPasswordValue = System.getenv("GXTUBE_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: keystorePassword

android {
    namespace = "de.abilas.gxtube"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.abilas.gxtube"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("GXTUBE_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("GXTUBE_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        create("gxtube") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = keyAliasName
            keyPassword = keyPasswordValue
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("gxtube")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("gxtube")
        }
    }

    compileOptions {
        // NewPipe Extractor nutzt java.nio / java.time – auf Android < 13 per Desugaring
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/README.md",
                "META-INF/CHANGES",
                "META-INF/COPYRIGHT",
                "META-INF/DEPENDENCIES",
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

tasks.withType<Test>().configureEach {
    // Online-Test (YouTube erreichbar?) nur, wenn ausdrücklich gewünscht
    environment("GXTUBE_ONLINE_TESTS", System.getenv("GXTUBE_ONLINE_TESTS") ?: "")
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.newpipe.extractor)
    implementation(libs.nanojson)

    testImplementation(libs.junit)
}
