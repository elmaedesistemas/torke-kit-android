plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "today.torke.kit.sensors"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":torke-kit"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "torke-kit-sensors"
            afterEvaluate { from(components["release"]) }
        }
    }
}
