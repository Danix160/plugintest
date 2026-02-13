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
        val posterUrl = fixUrlNull(
            img?.attr("data-src") ?: img?.attr("src")
        )

        val isTv = href.contains("/serie-tv/") || title.contains("serie tv", true)

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
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
            // Per i film, passiamo l'URL della pagina come 'data' per loadLinks
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
        // Se il data non è un URL del nostro sito, proviamo a caricarlo direttamente
        if (data.startsWith("http") && !data.contains("cineblog001")) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

        val doc = app.get(data).document
        
        // 1. Estrazione dai Mirror Principali (film2.txt) [cite: 91, 92, 93]
        doc.select("ul._player-mirrors li[data-link]").forEach { li ->
            val link = li.attr("data-link")
            if (link.isNotBlank() && !link.contains("mostraguarda.stream")) {
                loadExtractor(fixUrl(link), data, subtitleCallback, callback)
            }
        }

        // 2. Estrazione dai Server Alternativi (nascosti) [cite: 96, 97, 98]
        doc.select("div._hidden-mirrors li[data-link]").forEach { li ->
            val link = li.attr("data-link")
            if (link.isNotBlank()) {
                loadExtractor(fixUrl(link), data, subtitleCallback, callback)
            }
        }

        // 3. Fallback per player classici o iframe (se presenti) 
        doc.select("iframe#_player, iframe[src*='embed']").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
