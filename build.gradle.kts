plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
}

tasks.register<Copy>("assembleDebug") {
    dependsOn(":app:assembleDebug")
    from(layout.projectDirectory.file("app/build/outputs/apk/debug/app-debug.apk")) {
        rename { "Wi-Call-v2-Offline-Debug.apk" }
    }
    into(layout.buildDirectory.dir("outputs/apk"))
}
