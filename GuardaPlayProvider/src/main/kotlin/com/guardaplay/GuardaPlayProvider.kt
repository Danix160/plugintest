package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/fantascienza/" to "Fantascienza",
        "$mainUrl/category/avventura/" to "Avventura"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        
        // HashSet per filtrare i duplicati basandosi sull'URL (data)
        val seenUrls = mutableSetOf<String>()
        
        val items = document.select("li[id^=post-], article.post").mapNotNull { 
            val res = it.toSearchResult()
            if (res != null && seenUrls.add(res.url)) res else null
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a.lnk-blk")?.attr("href") 
            ?: this.selectFirst("a")?.attr("href") 
            ?: return null

        val title = this.selectFirst("h2.entry-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.replace("Image ", "")
            ?: return null
        
        val img = this.selectFirst("img")
        val posterUrl = img?.let { 
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.startsWith("//")) "https:$src" else src
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = commonHeaders).document
        
        val seenUrls = mutableSetOf<String>()
        return document.select("li[id^=post-], article.post").mapNotNull { 
            val res = it.toSearchResult()
            if (res != null && seenUrls.add(res.url)) res else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".post-thumbnail img, .poster img")?.let { img ->
            val src = img.attr("src").ifEmpty { img.attr("data-src") }
            if (src.startsWith("//")) "https:$src" else src
        }
        val plot = document.selectFirst(".description p, .entry-content p")?.text()
        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = commonHeaders)
        val document = response.document

        // Caricamento iframe "trembed" (visto nei file sorgente)
        val embedUrl = document.selectFirst("iframe[src*='trembed=']")?.attr("src")
            ?: document.selectFirst("iframe[data-src*='trembed=']")?.attr("data-src")

        val finalHtml = if (!embedUrl.isNullOrEmpty()) {
            app.get(embedUrl, headers = commonHeaders).text
        } else {
            response.text
        }

        // 1. Logica LoadM
        val videoId = Regex("""(?:id|video_id)["']?\s*[:=]\s*["']([^"']+)""").find(finalHtml)?.groupValues?.get(1)
            ?: Regex("""loadm\.cam/e/([^"'?]+)""").find(finalHtml)?.groupValues?.get(1)
            ?: Regex("""/e/([^"'?]+)""").find(finalHtml)?.groupValues?.get(1)

        if (videoId != null) {
            val apiUrl = "https://loadm.cam/api/v1/video?id=$videoId&r=guardaplay.space"
            try {
                val apiRes = app.get(apiUrl, headers = mapOf(
                    "Referer" to "https://loadm.cam/",
                    "User-Agent" to commonHeaders["User-Agent"]!!,
                    "X-Requested-With" to "XMLHttpRequest"
                ))
                val body = apiRes.text
                val finalUrl = Regex("""https?://[^\s"'<>]+(?:\.m3u8|\.mp4)[^\s"'<>]*""").find(body)?.value 

                if (finalUrl != null) {
                    callback.invoke(
                        newExtractorLink("LoadM", "LoadM - Guardaplay", finalUrl.replace("\\/", "/"), 
                            if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = "https://loadm.cam/"
                        }
                    )
                }
            } catch (e: Exception) { }
        }

        // 2. Altri estrattori
        val regex = Regex("""https?://(?:vidhide|voe|streamwish|mixdrop|filemoon|vood|maxstream)[^\s"'<>\\\/]+""")
        regex.findAll(finalHtml).forEach { match ->
            loadExtractor(match.value.replace("\\/", "/"), subtitleCallback, callback)
        }

        return true
    }
}
