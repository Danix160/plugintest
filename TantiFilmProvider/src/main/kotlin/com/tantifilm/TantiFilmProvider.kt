package com.tantifilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.Interceptor
import okhttp3.Response

class TantiFilmProvider : MainAPI() {
    override var mainUrl = "https://tanti-film.stream"
    override var name = "TantiFilm"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    // Header molto robusti per simulare un browser reale
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.8,en-US;q=0.5,en;q=0.3",
        "Referer" to "$mainUrl/",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        
        // Eseguiamo la chiamata con gli header rinforzati
        val response = app.get(url, headers = commonHeaders, allowRedirects = true)
        val document = response.document
        
        // Selettore d'emergenza: cerchiamo TUTTI gli articoli o elementi con link che sembrano film
        val items = document.select("article, .item, .result-item, .poster").mapNotNull { element ->
            val link = element.selectFirst("a[href*='/film/'], a[href*='/serie-tv/']") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            
            // Evitiamo link circolari o menu
            if (href == "$mainUrl/film/" || href == "$mainUrl/serie-tv/") return@mapNotNull null

            val title = element.selectFirst("h2, h3, .title")?.text()?.trim() 
                ?: link.attr("title").trim()
                ?: element.selectFirst("img")?.attr("alt")?.trim()
                ?: return@mapNotNull null

            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("data-lazy-src") ?: img?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }.distinctBy { it.url } // Rimuove i duplicati
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document

        return document.select("div.result-item, article").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".title, h3")?.text() ?: link.text()
            val href = fixUrl(link.attr("href"))
            val img = element.selectFirst("img")
            
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = img?.attr("src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".poster img, .image img")?.attr("src")
        val plot = document.selectFirst(".description p, #sinopsis p")?.text()

        val episodes = mutableListOf<Episode>()
        val isSeries = document.select("#seasons, .season").isNotEmpty()

        if (isSeries) {
            document.select(".season").forEach { season ->
                val sNum = season.selectFirst(".title")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
                season.select(".episodios li").forEach { ep ->
                    val a = ep.selectFirst("a") ?: return@forEach
                    episodes.add(newEpisode(fixUrl(a.attr("href"))) {
                        this.name = a.text()
                        this.season = sNum
                        this.episode = ep.selectFirst(".numerando")?.text()?.substringAfter("-")?.trim()?.toIntOrNull()
                    })
                }
            }
        } else {
            // Estrazione link video (VOE, MixDrop ecc)
            val sources = mutableListOf<String>()
            document.select("ul#playeroptionsul li").forEach { 
                val dataUrl = it.attr("data-url")
                if (dataUrl.isNotBlank()) sources.add(dataUrl)
            }
            
            // Fallback iframe
            document.select("iframe").forEach { 
                val src = it.attr("src")
                if (src.contains("http") && !src.contains("facebook")) sources.add(src)
            }

            if (sources.isNotEmpty()) {
                episodes.add(newEpisode(sources.joinToString("###")) {
                    this.name = "Film"
                })
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
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
        data.split("###").forEach { 
            loadExtractor(it, subtitleCallback, callback)
        }
        return true
    }
}
