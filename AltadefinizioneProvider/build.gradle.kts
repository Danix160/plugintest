android {
    // Mantieni le tue configurazioni compileSdk ecc. qui sopra
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}

cloudstream {
    // Configurazione diretta (più sicura)
    prefix = "Altadefinizione"
    displayName = "Altadefinizione"
    
    // Si usa versionCode invece di version
    versionCode = 1
    description = "Altadefinizionez.sbs"
    authors = listOf("Danix")
    
    status = 1
    // Assicurati che listOf sia scritto con la 'o' e non con lo '0'
    tvTypes = listOf("Movie", "TvSeries")
    
    requiresResources = false
    language = "it"
    // Ho rimosso l'icona di ToonItalia per Altadefinizione, 
    // puoi metterne una specifica o lasciarla vuota ""
    iconUrl = "https://altadefinizionez.sbs/templates/Alta/images/favicon/apple-touch-icon.png"
}
