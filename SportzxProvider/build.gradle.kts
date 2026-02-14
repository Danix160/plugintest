android {
    // ... altre configurazioni (compileSdk, ecc.) ...

    kotlinOptions {
        jvmTarget = "1.8"
        // Questa riga dice al compilatore di ignorare che la libreria è "troppo nuova"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "Sportzx"
    extra["displayName"] = "Sportzx"
    
    version = 19
    description = "Sportzx Live Sports"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Live")
    requiresResources = false
    language = "it"
    iconUrl = "https://sportzx.cc/wp-content/uploads/2025/10/sportzx.webp"
}
