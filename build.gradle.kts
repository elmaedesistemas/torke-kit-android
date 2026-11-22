plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// JitPack publishes every module as com.github.elmaedesistemas.torke-kit-android:<module>:<tag>.
allprojects {
    group = "today.torke.kit"
    version = "0.2.0"
}
