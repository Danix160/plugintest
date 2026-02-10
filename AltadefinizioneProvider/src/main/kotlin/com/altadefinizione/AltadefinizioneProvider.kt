package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AltaDefinizioneProvider : MainAPI() {
    override var mainUrl = "https://altadefinizionez.sbs"
    override var name = "Altadefinizione"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    )

    // Ho aggiornato le rotte basandomi sul menu reale del sito
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Inseriti",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/animazione/" to "Animazione",
        "$mainUrl/tendenze/" to "Tendenze"
    )

    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        // Selettore aggiornato: i film sono in div con classe 'movie' o dentro 'movie-poster'
        val home = document.select(".movie-poster, .movie-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2 a, .movie-title a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        
        return if (href.contains("/serie-tv/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select(".movie-poster, .movie-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = document.selectFirst(".movie-description, .full-text")?.text()?.trim()
        
        val isSeries = url.contains("/serie-tv/")

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            // Parsing episodi per Gumball e simili
            document.select(".episodes-list li").forEach {
                val name = it.text().trim()
                // Alcuni siti DLE usano data-id per caricare i link via AJAX
                val data = it.attr("data-id").ifEmpty { it.selectFirst("a")?.attr("href") ?: url }
                episodes.add(Episode(fixUrl(data), name))
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        // Se il "data" è un ID (per le serie), dobbiamo chiamare l'endpoint AJAX
        // Per ora cerchiamo gli iframe standard come quello Dropload che hai mandato
        val document = app.get(data).document
        
        document.select("iframe").forEach { 
            val src = it.attr("src")
            if (src.contains("dropload") || src.contains("voe") || src.contains("mixdrop")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }
        
        // Controllo se ci sono script con link ai player
        val scriptData = document.select("script").html()
        val playerRegex = Regex("""https?://(?:dropload|voe|mixdrop|supervideo)\.[a-z]+/([a-zA-Z0-9]+)""")
        playerRegex.findAll(scriptData).forEach {
            loadExtractor(it.value, data, subtitleCallback, callback)
        }

        return true
    }
}
