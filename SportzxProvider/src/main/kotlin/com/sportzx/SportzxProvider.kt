package com.sportzx

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SportzxProvider : MainAPI() {
    override var mainUrl = "https://sportzx.cc" 
    override var name = "SportzX"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Live)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val res = app.get("$mainUrl/sportzx-live/", headers = commonHeaders)
        val doc = res.document
        val items = mutableListOf<SearchResponse>()

        val cards = doc.select("div.vsc-card")
        
        if (cards.isNotEmpty()) {
            cards.forEach { card ->
                val teams = card.select(".vsc-team-name").map { it.text().trim() }
                val time = card.selectFirst(".vsc-time")?.text() ?: ""
                val league = card.selectFirst(".vsc-league-text")?.text() ?: ""
                val imageUrl = card.selectFirst("img")?.attr("src")

                val title = if (teams.size >= 2) "${teams[0]} VS ${teams[1]}" else league
                
                items.add(newLiveSearchResponse(
                    name = "$time $title".trim(),
                    url = "$mainUrl/en-vivo/", 
                    type = TvType.Live
                ).apply { this.posterUrl = imageUrl })
            }
        } else {
            doc.select("a").forEach { 
                val text = it.text().lowercase()
                if (text.contains("vs") || text.contains("live") || text.contains("stream")) {
                    items.add(newLiveSearchResponse(it.text(), it.attr("href"), TvType.Live))
                }
            }
        }

        return newHomePageResponse(listOf(HomePageList("Eventi in Diretta", items)), false)
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("SportzX Live", url, url) {
            this.apiName = this@SportzxProvider.name
            this.type = TvType.Live
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = commonHeaders).document
        val links = mutableListOf<String>()
        doc.select("a.vsc-stream-link").forEach { links.add(it.attr("href")) }
        doc.select("iframe").forEach { links.add(it.attr("src")) }

        links.distinct().filter { it.isNotBlank() }.forEach { link ->
            val finalUrl = if (link.startsWith("/")) mainUrl + link else link
            try {
                val text = app.get(finalUrl, referer = data, headers = commonHeaders).text
                val m3u8 = Regex("""["'](https?.*?\.m3u8.*?)["']""").find(text)?.groupValues?.get(1)
                
                if (m3u8 != null) {
                    callback(
                        ExtractorLink(
                            this.name,
                            "Server HD",
                            m3u8,
                            finalUrl,
                            Qualities.P1080.value,
                            true
                        )
                    )
                }
            } catch (e: Exception) { }
        }
        return true
    }
}
