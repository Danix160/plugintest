package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "Toon Italia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    //Homepage
override suspend fun getMainPage(
        page: Int,
        request: HomePageRequest
    ): HomePageResponse {
        // Carichiamo la home page per estrarre i widget
        val document = app.get(mainUrl).document
        val homePageLists = mutableListOf<HomePageList>()

        // Selezioniamo tutti i widget che contengono liste di post (Updates, Anime, Serie TV, ecc.)
        document.select("div.rpwwt-widget").forEach { widget ->
            val title = widget.selectFirst("h2.widget-title")?.text()?.trim() ?: "Altro"
            
            val items = widget.select("ul li").mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href")
                val name = li.selectFirst("span.rpwwt-post-title")?.text() ?: a.text()
                val posterUrl = li.selectFirst("img")?.attr("src")

                // Creiamo l'oggetto risposta per la home
                newTvSeriesSearchResponse(name, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }

            if (items.isNotEmpty()) {
                homePageLists.add(HomePageList(title, items))
            }
        }

        // Aggiungiamo anche la sezione "I Più Visti" se presente (ha una struttura leggermente diversa)
        document.select("div.widget_post_views_counter_list_widget").forEach { widget ->
            val title = widget.selectFirst("h2.widget-title")?.text()?.trim() ?: "I Più Visti"
            val items = widget.select("ol li").mapNotNull { li ->
                val a = li.selectFirst("a.post-title") ?: return@mapNotNull null
                val href = a.attr("href")
                val name = a.text()
                val posterUrl = li.selectFirst("img")?.attr("src")

                newTvSeriesSearchResponse(name, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
            if (items.isNotEmpty()) {
                homePageLists.add(HomePageList(title, items))
            }
        }

        return HomePageResponse(homePageLists)
    }

//SEARCH
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
        
        // 1. CERCA NEI BOTTONI (Serie TV) - CORRETTO
        document.select("a[class*='maxbutton']").forEach { button ->
            val link = button.attr("href")
            if (link.startsWith("http") && !link.contains("share")) {
                episodes.add(newEpisode(link) { 
                    this.name = button.text().trim() 
                })
            }
        }

        // 2. CERCA NEI LINK TESTUALI (Film) - CORRETTO
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
        if (loadExtractor(data, data, subtitleCallback, callback)) return true

        val doc = app.get(data).document
        
        doc.select("iframe").map { it.attr("src") }.forEach { 
            loadExtractor(it, data, subtitleCallback, callback) 
        }

        return true
    }
}
