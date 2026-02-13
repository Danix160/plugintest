package com.cineblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.club"
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(mainUrl).document
        val items = doc.select(".promo-item, .movie-item, .m-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("In Evidenza", items)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val doc = app.get(url).document
        
        return doc.select(".m-item, .movie-item, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.contains("/tags/") || href.contains("/category/")) return null

        val title = this.selectFirst("h2, h3, .m-title")?.text() 
            ?: a.attr("title").ifEmpty { a.text() }
        
        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))

        val isTv = href.contains("/serie-tv/") || title.contains("serie tv", true)

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        
        val poster = fixUrlNull(
            doc.selectFirst("img._player-cover")?.attr("src") 
            ?: doc.selectFirst(".story-poster img, .m-img img, img[itemprop='image']")?.attr("src")
        )
        
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")
        val isSerie = url.contains("/serie-tv/") || doc.select("#tv_tabs").isNotEmpty()

        return if (isSerie) {
            val episodes = doc.select(".tt_series li, .episodes-list li").mapNotNull { li ->
                val link = li.selectFirst("a")
                if (link != null) {
                    val epData = link.attr("data-link").ifEmpty { link.attr("href") }
                    newEpisode(epData) { this.name = link.text().trim() }
                } else null
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // Per i film cerchiamo l'iframe del player nella pagina
            val movieData = doc.selectFirst("iframe[src*='mostraguarda'], iframe#_player")?.attr("src") ?: url
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
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
        // 1. Carichiamo la pagina passata (data può essere l'iframe o l'URL film)
        var doc = app.get(fixUrl(data)).document

        // 2. GESTIONE FAKE PLAYER (film.txt): 
        // Se troviamo il tasto che apre il vero player, carichiamo quella nuova pagina
        val realUrl = doc.selectFirst(".open-fake-url")?.attr("data-url")
            ?: doc.selectFirst("iframe#_player")?.attr("src")
        
        if (!realUrl.isNullOrBlank()) {
            doc = app.get(fixUrl(realUrl)).document
        }

        // 3. ESTRAZIONE MIRROR (film2.txt)
        // Estraiamo da tutti i li che hanno data-link
        doc.select("li[data-link]").forEach { li ->
            val link = li.attr("data-link")
            if (link.isNotBlank() && !link.contains("mostraguarda.stream")) {
                loadExtractor(fixUrl(link), data, subtitleCallback, callback)
            }
        }

        // 4. Fallback per iframe diretti (se non ci sono mirror list)
        if (doc.select("li[data-link]").isEmpty()) {
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("facebook") && !src.contains("google")) {
                    loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
