package com.sportzx

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Carichiamo la pagina sportzx-live che contiene la griglia vsc-grid
        val doc = app.get("$mainUrl/sportzx-live/", headers = headers).document
        val items = mutableListOf<SearchResponse>()

        // Basato sull'HTML reale del tuo file home.txt
        doc.select("div.vsc-card").forEach { card ->
            val teams = card.select("div.vsc-team-name").map { it.text().trim() }
            val time = card.selectFirst("div.vsc-time")?.text()?.trim() ?: ""
            val league = card.selectFirst("span.vsc-league-text")?.text()?.trim() ?: ""
            
            // Costruiamo un titolo leggibile: "10:30 - TeamA VS TeamB (League)"
            val title = if (teams.size >= 2) {
                "$time ${teams[0]} VS ${teams[1]} ($league)"
            } else if (teams.size == 1) {
                "$time ${teams[0]} ($league)"
            } else {
                league
            }

            if (title.isNotEmpty()) {
                items.add(newLiveSearchResponse(
                    title,
                    "$mainUrl/en-vivo/", // Le card sembrano puntare tutte alla pagina del player
                    TvType.Live
                ))
            }
        }
        
        return newHomePageResponse(listOf(HomePageList("Eventi Live", items)), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("title")?.text()?.replace(" - SportzX TV", "") ?: "Live Stream"

        return newLiveStreamLoadResponse(title, url, url) {
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
        val doc = app.get(data, headers = headers).document
        
        // Il sito usa un sistema di "modal" con link ai vari server
        val links = mutableListOf<String>()
        
        // 1. Cerchiamo i link diretti dei server (dal tuo file selezione.txt)
        doc.select("a.vsc-stream-link").forEach { links.add(it.attr("href")) }
        
        // 2. Cerchiamo eventuali iframe se i link sopra non ci sono
        if (links.isEmpty()) {
            doc.select("iframe").forEach { links.add(it.attr("src")) }
        }

        links.distinct().filter { it.isNotBlank() }.forEach { linkUrl ->
            // Se il link è relativo, lo rendiamo assoluto
            val fullUrl = if (linkUrl.startsWith("/")) mainUrl + linkUrl else linkUrl
            
            if (fullUrl.contains("topstream") || fullUrl.contains("clonemy") || fullUrl.contains("stream")) {
                try {
                    val iframeContent = app.get(fullUrl, referer = data, headers = headers).text
                    
                    // Regex specifica per file m3u8
                    val m3u8Regex = Regex("""(?:file|source|src|url)\s*[:=]\s*["'](https?.*?\.m3u8.*?)["']""")
                    val foundM3u8 = m3u8Regex.find(iframeContent)?.groupValues?.get(1)

                    if (foundM3u8 != null) {
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "Server " + fullUrl.substringAfter("://").substringBefore("."),
                                url = foundM3u8,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = fullUrl
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("SportzX", "Errore estrazione link: ${e.message}")
                }
            }
        }
        return true
    }
}
