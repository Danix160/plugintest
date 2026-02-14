package com.sportzx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addLiveElement
import org.jsoup.nodes.Element

class SportzxProvider : MainAPI() {
    override var mainUrl = "https://sportzx.cc"
    override var name = "SportzX"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Live)

    private val headers = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    )

    // CORRETTO: Adesso usa la firma corretta con HomePageRequest? e restituisce HomePageResponse?
    override suspend fun getMainPage(page: Int, request: HomePageRequest?): HomePageResponse? {
        val doc = app.get("$mainUrl/sportzx-live/", headers = headers).document
        val items = mutableListOf<SearchResponse>()

        doc.select("div.elementor-widget-container h3").forEach { 
            val title = it.text().trim()
            if (title.isNotEmpty()) {
                // CORRETTO: Uso di newLiveSearchResponse
                items.add(newLiveSearchResponse(
                    title,
                    "$mainUrl/en-vivo/",
                    TvType.Live
                ))
            }
        }
        // CORRETTO: Uso di newHomePageResponse con la lista di risultati
        return newHomePageResponse(listOf(HomePageList("Live Events", items)), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("title")?.text()?.replace(" - SportzX TV", "") ?: "Live Stream"

        // CORRETTO: Uso di newLiveStreamLoadResponse
        return newLiveStreamLoadResponse(
            title,
            url,
            TvType.Live,
            url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document
        
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.contains("topstream") || src.contains("clonemy") || src.contains("stream")) {
                val iframeRes = app.get(src, referer = data, headers = headers).text
                val m3u8Regex = Regex("""(?:file|source|src)\s*:\s*["'](https?.*?\.m3u8.*?)["']""")
                val foundUrl = m3u8Regex.find(iframeRes)?.groupValues?.get(1)

                if (foundUrl != null) {
                    // CORRETTO: Uso di newExtractorLink
                    callback.invoke(
                        newExtractorLink(
                            "SportzX Server",
                            "HD Stream",
                            foundUrl,
                            referer = src,
                            quality = Qualities.P1080.value,
                            isM3u8 = true
                        )
                    )
                }
            }
        }
        return true
    }
}
