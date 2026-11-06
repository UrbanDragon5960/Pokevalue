plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.pokevalue.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.pokevalue.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.6"
    }
    buildFeatures { compose = true; buildConfig = true }
    val supabaseUrl = project.findProperty("SUPABASE_URL")?.toString() ?: ""
    val supabaseAnonKey = project.findProperty("SUPABASE_ANON_KEY")?.toString() ?: ""
    defaultConfig {
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}