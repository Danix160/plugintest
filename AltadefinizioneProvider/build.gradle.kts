android {
    // ... altre configurazioni (compileSdk, ecc.) ...

    kotlinOptions {
        jvmTarget = "1.8"
        // Questa riga dice al compilatore di ignorare che la libreria è "troppo nuova"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "AltaDefinizione"
    extra["displayName"] = "AltaDefinizione"
    
    version = 1
    description = "AltaDefinizionez"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = ""
}
