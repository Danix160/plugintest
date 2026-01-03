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
    // 1. Scarichiamo la pagina e usiamo 'use' per assicurarci di chiudere le risorse (evita l'errore nel log)
    val response = app.get(data)
    val document = response.document

    // 2. ToonItalia spesso mette i link in pulsanti o dentro script. 
    // Cerchiamo ovunque: a, iframe, e persino dentro gli script.
    val potentialLinks = mutableSetOf<String>()
    
    document.select("a, iframe, source").forEach { 
        val href = it.attr("href")
        val src = it.attr("src")
        if (href.isNotEmpty()) potentialLinks.add(href)
        if (src.isNotEmpty()) potentialLinks.add(src)
    }

    potentialLinks.forEach { link ->
        // Salta link palesemente inutili per risparmiare tempo
        if (link.startsWith("javascript") || link.contains("facebook") || link.contains("twitter")) return@forEach

        // Caso A: Link VOE diretto o con redirect semplice
        if (link.contains("voe.sx") || link.contains("voe-un-block") || link.contains("crystaltreatmenteast.com")) {
            
            // Per i link ponte, dobbiamo ottenere l'URL finale dopo il redirect
            val finalUrl = if (link.contains("crystaltreatmenteast.com")) {
                // Eseguiamo una chiamata HEAD o GET veloce per seguire il redirect
                app.get(link, allowRedirects = true).url
            } else {
                link
            }

            if (finalUrl.contains("voe")) {
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        }
        
        // Caso B: Altri Host comuni (opzionale ma consigliato)
        else if (link.contains("speedvideo") || link.contains("mixdrop") || link.contains("delta")) {
            loadExtractor(link, data, subtitleCallback, callback)
        }
    }

    return true
}
}
