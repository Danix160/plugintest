android {
    // ... altre configurazioni (compileSdk, ecc.) ...

    kotlinOptions {
        jvmTarget = "1.8"
        // Questa riga dice al compilatore di ignorare che la libreria è "troppo nuova"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
}
cloudstream {
    extra["prefix"] = "GuardaPlay"
    extra["displayName"] = "GuardaPlay"
    
    version = 17
    description = "GuardaPlay Contiene Solo Film"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie")
    requiresResources = false
    language = "it"
    iconUrl = "https://guardaplay.space/wp-content/uploads/2025/12/cropped-guardaplaym.png"
}
