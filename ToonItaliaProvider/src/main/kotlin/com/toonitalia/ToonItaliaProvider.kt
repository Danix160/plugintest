package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
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
    // Scarichiamo il codice sorgente completo della pagina come stringa
    val response = app.get(data)
    val pageContent = response.text

    // 1. Cerchiamo tutti i link VOE o link ponte (crystaltreatment) usando una Regex
    // Questa cerca qualsiasi cosa che sembri un URL e contenga le parole chiave
    val linkRegex = Regex("""https?://[^\s"'<>]+(?:voe\.sx|voe-un-block|crystaltreatmenteast\.com)[^\s"'<>]*""")
    val matches = linkRegex.findAll(pageContent).map { it.value }.toSet()

    if (matches.isEmpty()) {
        // Se non troviamo nulla con la Regex, proviamo il metodo classico sugli iframe (piano B)
        response.document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("voe") || src.contains("crystal")) {
                processLink(src, data, subtitleCallback, callback)
            }
        }
    } else {
        matches.forEach { link ->
            processLink(link, data, subtitleCallback, callback)
        }
    }

    return true
}

// Funzione di supporto per gestire il redirect e l'estrazione
private suspend fun processLink(
    link: String, 
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit, 
    callback: (ExtractorLink) -> Unit
) {
    var finalUrl = link
    
    // Se è un link ponte, seguiamo il redirect per arrivare a VOE
    if (link.contains("crystaltreatmenteast.com")) {
        try {
            // allowRedirects = true è fondamentale
            val redirectResponse = app.get(link, referer = referer, allowRedirects = true, timeout = 10)
            finalUrl = redirectResponse.url
        } catch (e: Exception) {
            return // Se il link ponte è morto, passiamo al prossimo
        }
    }

    // Se ora abbiamo un link VOE valido, lanciamo l'estrattore
    if (finalUrl.contains("voe")) {
        loadExtractor(finalUrl, referer, subtitleCallback, callback)
    }
}
}
