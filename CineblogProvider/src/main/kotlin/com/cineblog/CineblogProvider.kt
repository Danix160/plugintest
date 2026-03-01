package com.cineblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.jsoup.nodes.Element

// =============================================================================
// ESTRATTORI DEDICATI
// =============================================================================

class DroploadExtractor : ExtractorApi() {
    override var name = "Dropload"
    override var mainUrl = "https://dropload.tv"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val body = app.get(url).body.string()
            val unpacked = getAndUnpack(body)
            val videoUrl = Regex("""file\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(unpacked)?.groupValues?.get(1)

            videoUrl?.let {
                callback.invoke(
                    newExtractorLink(name, name, it, ExtractorLinkType.M3U8) {
                        this.referer = url
                        quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Dropload", "Error: ${e.message}")
        }
    }
}

class SupervideoExtractor : ExtractorApi() {
    override var name = "Supervideo"
    override var mainUrl = "https://supervideo.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url).body.string()
            val unpacked = getAndUnpack(response)
            
            val videoUrl = Regex("""file\s*:\s*"([^"]+.(?:m3u8|mp4)[^"]*)"""")
                .find(unpacked)?.groupValues?.get(1)

            videoUrl?.let {
                callback.invoke(
                    newExtractorLink(name, name, it, if(it.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = url
                        quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Supervideo", "Error: ${e.message}")
        }
    }
}

// =============================================================================
// PROVIDER PRINCIPALE: CINEBLOG01
// =============================================================================

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.autos"
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageList = mutableListOf<HomePageList>()
        val mainDoc = app.get(mainUrl).document

        val featured = mainDoc.select(".promo-item, .m-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (featured.isNotEmpty()) homePageList.add(HomePageList("In Evidenza", featured))

        val latest = mainDoc.select(".block-th").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (latest.isNotEmpty()) homePageList.add(HomePageList("Ultimi Aggiunti", latest))

        try {
            val tvDoc = app.get("$mainUrl/serie-tv/").document
            val tvItems = tvDoc.select(".block-th, .movie-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (tvItems.isNotEmpty()) homePageList.add(HomePageList("Serie TV", tvItems))
        } catch (e: Exception) { }

        return newHomePageResponse(homePageList, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.post("$mainUrl/index.php?do=search", data = mapOf(
            "do" to "search", "subaction" to "search", "story" to query
        )).document.select(".m-item, .movie-item, article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.contains("/tags/") || href.contains("/category/")) return null

        val title = this.selectFirst("h2, h3, .m-title")?.text() ?: a.attr("title")
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
        
        return if (href.contains("/serie-tv/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst("img._player-cover, .story-poster img, img[itemprop='image']")?.attr("src"))
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")

        val seasonContainer = doc.selectFirst(".tt_season")
        return if (seasonContainer != null) {
            val episodesList = mutableListOf<Episode>()
            doc.select(".tt_series .tab-content .tab-pane").forEachIndexed { index, pane ->
                val seasonNum = index + 1
                pane.select("li").forEach { li ->
                    val a = li.selectFirst("a[id^=serie-]") ?: return@forEach
                    val mainLink = a.attr("data-link").ifEmpty { a.attr("href") }
                    val mirrors = li.select(".mirrors a.mr").map { it.attr("data-link") }
                    
                    val allLinks = (listOf(mainLink) + mirrors).filter { it.isNotBlank() }.joinToString("|")
                    val epNum = a.text().toIntOrNull() ?: 1

                    episodesList.add(newEpisode(allLinks) {
                        this.name = "Episodio $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = poster // Anteprima episodio impostata
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
        data.split("|").forEach { link ->
            val fixedLink = fixUrl(link)
            
            when {
                fixedLink.contains("dropload") -> 
                    DroploadExtractor().getUrl(fixedLink, fixedLink, subtitleCallback, callback)
                
                fixedLink.contains("supervideo") -> 
                    SupervideoExtractor().getUrl(fixedLink, fixedLink, subtitleCallback, callback)
                
                fixedLink.startsWith("http") && !fixedLink.contains("cineblog001") -> 
                    loadExtractor(fixedLink, fixedLink, subtitleCallback, callback)
            }
        }
        return true
    }
}
