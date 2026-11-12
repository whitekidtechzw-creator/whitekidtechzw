plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "zw.whitekid.vpn"
    compileSdk = 34
    defaultConfig {
        applicationId = "zw.whitekid.vpn"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "1.14.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Built-in SlowDNS server (GitHub secrets). Empty = no built-in server.
        buildConfigField("String", "SLOWDNS_NS", "\"${System.getenv("WK_SLOWDNS_NS") ?: ""}\"")
        buildConfigField("String", "SLOWDNS_KEY", "\"${System.getenv("WK_SLOWDNS_KEY") ?: ""}\"")
        buildConfigField("String", "SLOWDNS_USER", "\"${System.getenv("WK_SLOWDNS_USER") ?: ""}\"")
        buildConfigField("String", "SLOWDNS_PASS", "\"${System.getenv("WK_SLOWDNS_PASS") ?: ""}\"")
        // SSL/SNI country list (GitHub secret WK_SSL_SERVERS_B64 = base64 of the JSON). Empty = use the assets file and in-app entries.
        buildConfigField("String", "SSL_SERVERS_B64", "\"${(System.getenv("WK_SSL_SERVERS_B64") ?: "").filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }}\"")
    }
    // Stable release key: the CI decodes it from the WK_KEYSTORE_B64 secret. Without it, falls back to the debug key.
    val wkKeystore = System.getenv("WK_KEYSTORE")?.let { file(it) }
    val haveKey = wkKeystore != null && wkKeystore.exists()
    signingConfigs {
        if (haveKey) create("release") {
            storeFile = wkKeystore
            storePassword = System.getenv("WK_STORE_PASS")
            keyAlias = System.getenv("WK_KEY_ALIAS")
            keyPassword = System.getenv("WK_KEY_PASS")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (haveKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WireGuard (official userspace Go backend)
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    // SSH
    implementation("com.github.mwiede:jsch:0.2.20")
    // QR codes for sharing
    implementation("com.google.zxing:core:3.5.3")
    // Xray/V2Ray core (VLESS, VMess, Trojan, SNI/TLS/Reality/WS/gRPC) - CI downloads libv2ray.aar into app/libs
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
