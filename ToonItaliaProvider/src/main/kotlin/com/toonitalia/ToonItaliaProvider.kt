package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.APIHolder

class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "Toon Italia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val title = titleHeader.text()
            val href = titleHeader.attr("href")
            val posterUrl = article.selectFirst("img")?.attr("src")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.entry-content img")?.attr("src")
        
        val plot = document.select("h3:contains(Trama) + p").text().ifEmpty {
            document.select("div.entry-content p").firstOrNull { it.text().length > 30 }?.text()
        }

        val episodes = mutableListOf<Episode>()
        
        document.select("a[class*='maxbutton']").forEach { button ->
            val link = button.attr("href")
            if (link.startsWith("http") && !link.contains("share")) {
                episodes.add(newEpisode(link) { 
                    this.name = button.text().trim() 
                })
            }
        }

        val contentLinks = document.select("div.entry-content a")
        contentLinks.forEach { a ->
            val href = a.attr("href")
            val text = a.text().trim()
            val isVideoHost = listOf("voe", "vidhide", "chuckle-tube", "mixdrop", "streamtape", "lulustream", "streamup").any { 
                href.contains(it) || text.contains(it, ignoreCase = true) 
            }
            
            if (isVideoHost) {
                episodes.add(newEpisode(href) {
                    this.name = if (text.length < 2) "Streaming" else text
                })
            }
        }

        return if (episodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }
//ESTRATTORE
  override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document

    // 1. Cerchiamo tutti i link e gli iframe
    document.select("a, iframe, source").forEach { element ->
        val link = element.attr("href").ifEmpty { element.attr("src") }
        if (link.isEmpty() || link.startsWith("javascript")) return@forEach

        // 2. Se è già un link VOE, caricalo subito
        if (link.contains("voe.sx") || link.contains("voe-un-block")) {
            loadExtractor(link, data, subtitleCallback, callback)
        } 
        
        // 3. Gestione link "Ponte" (crystaltreatmenteast, rebrandly, ecc.)
        else if (link.contains("crystaltreatmenteast.com") || link.contains("rebrand.ly") || link.contains("short")) {
            // Usiamo una richiesta che segue i redirect automaticamente senza scaricare tutto l'HTML se possibile
            val response = app.get(link, allowRedirects = true)
            val finalUrl = response.url
            
            if (finalUrl.contains("voe.sx")) {
                loadExtractor(finalUrl, link, subtitleCallback, callback)
            } else {
                // Se l'URL finale non è ancora VOE, cerchiamo dentro la pagina di atterraggio
                val doc2 = response.document
                val hiddenLink = doc2.selectFirst("a[href*=voe.sx], iframe[src*=voe.sx], script")?.let {
                    it.attr("href").ifEmpty { it.attr("src") }
                }
                if (hiddenLink?.contains("voe.sx") == true) {
                    loadExtractor(hiddenLink, finalUrl, subtitleCallback, callback)
                }
            }
        }
    }
    return true
}
}
