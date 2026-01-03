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
            val isVideoHost = listOf("voe", "vidhide", "chuckle-tube", "mixdrop", "streamtape").any { 
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

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // Cerchiamo tutti i link (href) nell'HTML dell'episodio
    // Il pattern cerca chuckletube, luluvdo e i classici voe/lulu
    val linkRegex = Regex("""https?://[^\s"'<>]+(?:chuckle-tube\.com|luluvdo\.com|voe\.sx|lulustream\.com|short\.ink)[^\s"'<>]*""")
    
    val matches = linkRegex.findAll(data).map { it.value }.toSet()

    matches.forEach { link ->
        // 1. Gestione VOE tramite ponte Chuckle-Tube
        if (link.contains("chuckle-tube.com")) {
            // Chuckle-tube è un redirect diretto a VOE
            val finalUrl = app.get(link, allowRedirects = true).url
            if (finalUrl.contains("voe")) {
                loadExtractor(finalUrl, link, subtitleCallback, callback)
            }
        } 
        
        // 2. Gestione LuluStream tramite ponte Luluvdo
        else if (link.contains("luluvdo.com")) {
            // Trasformiamo l'URL luluvdo in un URL lulustream standard se necessario
            // o seguiamo il redirect
            val finalUrl = app.get(link, allowRedirects = true).url
            loadExtractor(finalUrl, link, subtitleCallback, callback)
        }
        
        // 3. Altri link diretti (se presenti)
        else {
            loadExtractor(link, data, subtitleCallback, callback)
        }
    }

    return true
}
}
