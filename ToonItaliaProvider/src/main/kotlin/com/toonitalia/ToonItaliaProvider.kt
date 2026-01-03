override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // 1. Scarichiamo la pagina dell'episodio
    val doc = app.get(data).document
    
    // 2. Cerchiamo tutti i link agli iframe dei player (ToonItalia li usa molto)
    // Spesso sono dentro div con classe 'tab-content' o simili
    val playerFrames = doc.select("iframe[src*=/p/], iframe[src*=api], iframe[src*=embed]")
    
    playerFrames.forEach { frame ->
        val frameUrl = frame.attr("src")
        
        // 3. Entriamo dentro l'iframe per trovare il link finale (VOE/Crystal)
        val frameDoc = app.get(frameUrl, referer = data).text
        
        // Usiamo la Regex per trovare il link VOE o il ponte CrystalTreat
        val linkRegex = Regex("""https?://[^\s"'<>]+(?:voe\.sx|voe-un-block|crystaltreatmenteast\.com)[^\s"'<>]*""")
        linkRegex.findAll(frameDoc).forEach { match ->
            val foundLink = match.value
            
            // Gestiamo il redirect se è un link ponte
            if (foundLink.contains("crystaltreatmenteast.com")) {
                val finalUrl = app.get(foundLink, referer = frameUrl, allowRedirects = true).url
                if (finalUrl.contains("voe")) {
                    loadExtractor(finalUrl, frameUrl, subtitleCallback, callback)
                }
            } else {
                loadExtractor(foundLink, frameUrl, subtitleCallback, callback)
            }
        }
    }

    // Piano B: Se non ci sono iframe, cerchiamo i link diretti nella pagina (vecchio metodo)
    val directLinks = doc.select("a[href*=voe], a[href*=crystal]")
    directLinks.forEach { a ->
        val href = a.attr("href")
        loadExtractor(href, data, subtitleCallback, callback)
    }

    return true
}
