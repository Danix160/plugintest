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
        // Selettore espanso per coprire sia promo che liste standard
        val items = doc.select(".promo-item, .movie-item, .m-item, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("In Evidenza", items)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Questa è la stringa esatta per i siti DLE come questo
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val doc = app.get(url).document
        
        // La ricerca usa quasi sempre .m-item o .m-title
        return doc.select(".m-item, .movie-item, article, .m-img").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Cerchiamo il link principale. Nella ricerca spesso è dentro .m-title o sopra l'immagine
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        
        // Filtriamo link inutili
        if (href.contains("/tags/") || href.contains("/category/") || href == mainUrl) return null

        // Titolo: lo cerchiamo in vari posti (h3, h2, o attributo title)
        val title = this.selectFirst("h2, h3, .m-title, .promo-title")?.text() 
            ?: a.attr("title").ifEmpty { a.text() }
        
        if (title.isNullOrBlank() || title.length < 2) return null

        // Immagine: cerchiamo sia src che data-src
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src") ?: img?.attr("src") ?: img?.attr("data-original")
        )

        // Capisce se è una serie TV dal link o dal titolo
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
        val title = doc.selectFirst("h1, .m-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst(".film-poster img, .m-img img, .poster img")?.attr("src"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        
        // Controllo se ci sono tab o liste di episodi
        val isSerie = url.contains("/serie-tv/") || doc.select("#tv_tabs, .tt_series, .episodes-list").isNotEmpty()

        return if (isSerie) {
            val episodes = doc.select(".tt_series li, .episodes-list li, #tv_tabs li").mapNotNull { li ->
                val link = li.selectFirst("a")
                if (link != null) {
                    val data = link.attr("data-link").ifEmpty { link.attr("href") }
                    if (data.isBlank()) return@mapNotNull null
                    newEpisode(data) { 
                        this.name = link.text().trim() 
                    }
                } else null
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
        // Se il dato è già un link a un hoster (comune per gli episodi serie TV)
        if (data.startsWith("http") && !data.contains(mainUrl)) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

        // Per i film, carichiamo la pagina e cerchiamo i link
        val doc = app.get(data).document
        // Selettore per i vari hoster supportati dagli extractor di CloudStream
        doc.select("a[href*='mixdrop'], a[href*='supervideo'], a[href*='vidoza'], a[href*='streamtape']")
            .forEach { 
                val link = it.attr("href")
                val finalUrl = if (link.contains("/vai/")) {
                    try { app.get(link).url } catch (e: Exception) { link }
                } else link
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        return true
    }
}
