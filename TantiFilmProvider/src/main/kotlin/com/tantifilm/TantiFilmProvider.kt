package com.tantifilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.nodes.Element

class TantiFilmProvider : MainAPI() {
    override var mainUrl = "https://tanti-film.stream" // Usiamo HTTPS
    override var name = "TantiFilm"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.8,en-US;q=0.5,en;q=0.3",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        
        // Selettore espanso: articoli o div all'interno del contenuto principale
        val items = document.select("div#archive-content article, div.items article, article.item").mapNotNull { element ->
            val titleHeader = element.selectFirst("div.data h3 a, h3 a, h2 a") ?: return@mapNotNull null
            val title = titleHeader.text().trim()
            val href = fixUrl(titleHeader.attr("href"))
            
            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document

        return document.select("div.result-item, article.item").mapNotNull { element ->
            val link = element.selectFirst("div.image a, div.poster a, h3 a") ?: return@mapNotNull null
            val title = element.selectFirst("div.details div.title a, h3 a")?.text()?.trim() 
                ?: element.selectFirst("img")?.attr("alt")?.trim() 
                ?: "Senza Titolo"
            
            val href = fixUrl(link.attr("href"))
            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src") ?: img?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        
        val title = document.selectFirst("div.data h1, h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.poster img")?.attr("src") 
            ?: document.selectFirst("div.image img")?.attr("src")
        val plot = document.selectFirst("div.description p, #sinopsis p, div.wp-content p")?.text()

        val episodes = mutableListOf<Episode>()
        
        // Controllo Stagioni/Episodi (Selettore specifico per TantiFilm)
        val seasonElements = document.select("div#seasons div.seasons, div.season")
        val isSeries = seasonElements.isNotEmpty()

        if (isSeries) {
            seasonElements.forEach { season ->
                val sNum = season.selectFirst("span.title")?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 1
                season.select("ul.episodios li").forEach { ep ->
                    val epLink = ep.selectFirst("a") ?: return@forEach
                    val epHref = fixUrl(epLink.attr("href"))
                    val epName = ep.selectFirst("div.episodiotitle a")?.text() ?: ep.selectFirst("a")?.text()
                    val eNum = ep.selectFirst("div.numerando")?.text()?.substringAfter("-")?.trim()?.toIntOrNull()
                    
                    episodes.add(newEpisode(epHref) {
                        this.name = epName
                        this.season = sNum
                        this.episode = eNum
                    })
                }
            }
        } else {
            // Estrazione link Film (Spesso caricati via AJAX o nascosti in tab)
            val movieLinks = mutableListOf<String>()
            
            // Link nei tab/player sources
            document.select("ul#playeroptionsul li, div.source-box a").forEach {
                val href = it.attr("data-url") ?: it.attr("href")
                if (!href.isNullOrBlank() && href.startsWith("http")) {
                    movieLinks.add(href)
                }
            }
            
            // Iframe diretti
            document.select("div.embed-responsive iframe, div.video-player iframe").forEach {
                val src = it.attr("src")
                if (src.contains("http")) movieLinks.add(fixUrl(src))
            }

            if (movieLinks.isNotEmpty()) {
                episodes.add(newEpisode(movieLinks.distinct().joinToString("###")) {
                    this.name = "Riproduci Film"
                })
            }
        }

        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, tvType, episodes.firstOrNull()?.data ?: "") {
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
        data.split("###").forEach { url ->
            val cleanUrl = url.trim()
            if (cleanUrl.contains("http")) {
                loadExtractor(cleanUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
