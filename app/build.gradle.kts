plugins { id("com.android.application") }
android {
    namespace = "com.aurikqq.noswidgets"
    compileSdk = 36
    sourceSets.getByName("release").assets.srcDir("src/debug/assets")
    val releaseStore = System.getenv("NOTHING_KEYSTORE")
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs.create("privateRelease") {
            storeFile = file(releaseStore)
            storePassword = System.getenv("NOTHING_STORE_PASSWORD")
            keyAlias = System.getenv("NOTHING_KEY_ALIAS")
            keyPassword = System.getenv("NOTHING_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("privateRelease")
    }
    defaultConfig { applicationId = "com.aurikqq.noswidgets"; minSdk = 31; targetSdk = 36; versionCode = 11; versionName = "1.0.0" }
}
dependencies { implementation("com.google.android.material:material:1.14.0"); implementation("androidx.work:work-runtime:2.12.0"); testImplementation("junit:junit:4.13.2") }
