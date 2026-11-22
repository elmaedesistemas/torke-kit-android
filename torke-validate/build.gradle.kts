// The torke-validate command: TorkeKit's times against dragstrip timeslips.
// ./gradlew :torke-validate:installDist  →  torke-validate/build/install/torke-validate/bin/torke-validate
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":torke-kit"))
}

application {
    applicationName = "torke-validate"
    mainClass.set("today.torke.validate.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootDir
}
