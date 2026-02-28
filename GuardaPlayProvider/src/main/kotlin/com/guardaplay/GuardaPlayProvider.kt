package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/fantascienza/" to "Fantascienza"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        
        val items = document.select("article, .post, .item, .post-thumbnail").mapNotNull { 
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") 
            ?: this.parent()?.selectFirst("a")?.attr("href") ?: return null
            
        val title = this.selectFirst("img")?.attr("alt")?.replace("Image ", "")?.trim()
            ?: this.selectFirst(".entry-title")?.text() ?: "Senza Titolo"
        
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.let {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") -> "https://image.tmdb.org/t/p/w500$src"
                else -> src
            }
        }

        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = commonHeaders).document
        return document.select("article, .post, .post-thumbnail").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")
        val year = document.selectFirst(".year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val plot = document.selectFirst(".description p, .entry-content p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = commonHeaders)
        val html = response.text

        // 1. ESTRAZIONE VIDSTACK (Il file .txt visto nel player che funge da M3U8)
        val vidstackRegex = Regex("""src=["'](https?://[^"']+/v4/xq/[^"']+\.txt)["']""")
        vidstackRegex.findAll(html).forEach { match ->
            val streamUrl = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    name = "LoadM (Vidstack)",
                    source = "LoadM Server HQ",
                    url = streamUrl,
                    referer = "https://loadm.cam/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        }

        // 2. METODO API TRADIZIONALE LOADM
        val videoId = Regex("""video_id\s*[:=]\s*["']([^"']+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""/e/([^"'?]+)""").find(html)?.groupValues?.get(1)

        if (videoId != null) {
            val apiUrl = "https://loadm.cam/api/v1/video?id=$videoId&r=guardaplay.space"
            try {
                val apiRes = app.get(apiUrl, headers = mapOf(
                    "Referer" to "https://loadm.cam/",
                    "X-Requested-With" to "XMLHttpRequest"
                )).text
                
                val finalUrl = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4)[^\s"'<>]*""").find(apiRes)?.value
                if (finalUrl != null) {
                    val cleanUrl = finalUrl.replace("\\/", "/")
                    callback.invoke(
                        newExtractorLink(
                            name = "LoadM API",
                            source = "LoadM Server",
                            url = cleanUrl,
                            referer = "https://loadm.cam/",
                            quality = Qualities.P1080.value,
                            type = if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            } catch (e: Exception) { }
        }

        // 3. ESTRATTORI STANDARD (Voe, Streamwish, etc.)
        val document = response.document
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("google")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        return true
    }
}
