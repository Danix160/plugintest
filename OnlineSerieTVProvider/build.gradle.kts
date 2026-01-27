android {
    // ... altre configurazioni
    kotlinOptions {
        jvmTarget = '1.8'
        freeCompilerArgs += ["-Xskip-metadata-version-check"] // <--- Aggiungi questa riga
    }
}
cloudstream {
    extra["prefix"] = "OnlineSerieTV"
    extra["displayName"] = "OnlineSerieTV"
    
    version = 1
    description = "OnlineSerietv.com/Prova"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Cartoon")
    requiresResources = false
    language = "it"
    iconUrl = "https://onlineserietv.online/images/logo.svg"
}
