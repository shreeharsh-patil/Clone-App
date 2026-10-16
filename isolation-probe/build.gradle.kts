plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
dependencyLocking { lockAllConfigurations() }
android {
    namespace = "org.parallelnest.probe"
    compileSdk = 36
    defaultConfig { applicationId = "org.parallelnest.probe"; minSdk = 33; targetSdk = 36; versionCode = providers.gradleProperty("probeVersionCode").orElse("1").get().toInt(); versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
