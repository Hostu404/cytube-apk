import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is opt-in via a gitignored keystore.properties (see
// keystore.properties.example) so the project still builds — unsigned — for
// anyone who clones it without a keystore of their own. Nothing secret ever
// lives in this build file or in git.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseKeystore) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.cytube.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cytube.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.5.1"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            // R8 is safe to turn on now that the keep rules for every
            // reflection-touching dependency (Socket.IO, OkHttp/okio,
            // NewPipeExtractor) live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Core library desugaring is deliberately OFF.
        //
        // It rewrites java.util.stream.* to j$.util.stream.*, and it did not
        // apply that rewrite consistently across NewPipeExtractor and its
        // nanojson dependency: the call site still looked for
        //   streamAsJsonObjects()Ljava/util/stream/Stream;
        // while the rewritten JsonArray returned j$/util/stream/Stream, so
        // every extraction died with NoSuchMethodError.
        //
        // minSdk is 26 and java.util.stream has been available since API 24,
        // so there is nothing here that needs desugaring in the first place.
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // Needed to reference BuildConfig.VERSION_NAME from the home screen's
        // title bar; AGP 8+ no longer generates BuildConfig unless asked.
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    // Stable collection types Compose's compiler can actually recognize as
    // immutable (a plain List/Map param forces every composable that takes
    // one to be non-skippable — see ChannelViewModel.ChannelUiState). Also
    // gives the chat/playlist/user-list state real structural-sharing
    // add/remove instead of copying the whole list per update.
    implementation(libs.kotlinx.collections.immutable)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    // Backs the on-disk media cache (see Graph.mediaCache / NativePlayerHandle) —
    // SimpleCache's recommended constructor takes a StandaloneDatabaseProvider
    // to index cached content in SQLite rather than the slower legacy flat-file
    // index the deprecated no-database constructor falls back to.
    implementation(libs.androidx.media3.database)
    // MediaSession: exposes playback to the system — hardware media keys
    // (a Fire TV remote's dedicated play/pause button), Alexa's "pause"/
    // "resume" voice commands, and any system Now Playing UI. All routed to
    // whichever app currently has the active session, which Media3 manages
    // for us as long as one exists (see PlayerSurface's ExoSurface).
    implementation(libs.androidx.media3.session)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.webkit)

    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.socketio)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.newpipe.extractor)
}
