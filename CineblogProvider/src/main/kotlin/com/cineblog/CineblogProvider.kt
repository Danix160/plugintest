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
        val home = mutableListOf<HomePageList>()
        
        // Definiamo le sezioni con i relativi URL
        val sections = listOf(
            Pair("$mainUrl/film/", "Ultimi Film"),
            Pair("$mainUrl/serie-tv/", "Ultime Serie TV"),
            Pair(mainUrl, "In Evidenza")
        )

        sections.forEach { (url, title) ->
            try {
                val response = app.get(url).text
                val doc = org.jsoup.Jsoup.parse(response)
                
                // Selettore differenziato: 
                // In home spesso sono 'promo-item', nelle sezioni sono 'movie-item' o dentro 'main-content'
                val items = doc.select("div.promo-item, div.movie-item, article.movie-item, .col-sm-4").mapNotNull {
                    it.toSearchResult()
                }.distinctBy { it.url } // Evitiamo duplicati nella stessa riga

                if (items.isNotEmpty()) {
                    home.add(HomePageList(title, items))
                }
            } catch (e: Exception) {
                // Errore nel caricamento di una sezione
            }
        }

        return newHomePageResponse(home, false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Cerchiamo il link principale che contiene il titolo
        val a = this.selectFirst("a") ?: return null
        val title = a.attr("title").ifEmpty { this.selectFirst("h2, h3")?.text() } ?: return null
        if (title.isBlank()) return null
        
        val href = fixUrl(a.attr("href"))
        
        // Cerchiamo l'immagine provando diversi attributi comuni (lazy loading)
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src") ?: 
            img?.attr("data-original") ?: 
            img?.attr("src")
        )

        // Logica migliorata per il tipo di contenuto
        val isTv = href.contains("/serie-tv/") || 
                   title.contains("serie tv", true) || 
                   title.contains("stagion", true)

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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        return document.select("div.promo-item, div.movie-item, article, .result-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.film-poster img, .poster img")?.attr("src"))
        val plot = document.selectFirst("meta[name=description], .plot-text")?.attr("content") ?: 
                   document.selectFirst(".item-desc")?.text()
        
        val isSerie = url.contains("/serie-tv/") || document.select("#tv_tabs, .tt_series").isNotEmpty()

        return if (isSerie) {
            val episodesList = mutableListOf<Episode>()
            
            document.select("div.tt_series div.tab-pane ul li, .episodes-list li").forEach { li ->
                val a = li.selectFirst("a")
                if (a != null) {
                    val epTitle = a.text()
                    val epLink = a.attr("data-link").ifEmpty { a.attr("href") }

                    episodesList.add(newEpisode(epLink) {
                        this.name = epTitle
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
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
        // Se i dati contengono già un link diretto (es. da serie tv)
        if (data.startsWith("http") && !data.contains(mainUrl)) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

        // Altrimenti cerchiamo i link nella pagina
        val doc = app.get(data).document
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
