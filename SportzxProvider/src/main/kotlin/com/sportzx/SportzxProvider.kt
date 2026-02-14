package com.sportzx

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class SportzxProvider : MainAPI() {
    // Il dominio base per i referer
    override var mainUrl = "https://sportzx.cc" 
    override var name = "SportzX"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Live)

    // Definiamo l'URL specifico della home con la griglia vsc-grid
    private val liveUrl = "$mainUrl/sportzx-live/"

    private val headers = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Fondamentale: usiamo liveUrl invece di mainUrl
        val doc = app.get(liveUrl, headers = headers).document
        val items = mutableListOf<SearchResponse>()

        // Selettori basati sulla struttura vsc-card del tuo file home.txt
        doc.select("div.vsc-card").forEach { card ->
            val teams = card.select("div.vsc-team-name").map { it.text().trim() }
            val time = card.selectFirst("div.vsc-time")?.text()?.trim() ?: ""
            val league = card.selectFirst("span.vsc-league-text")?.text()?.trim() ?: ""
            val image = card.selectFirst("img.vsc-league-logo")?.attr("src")

            val title = if (teams.size >= 2) {
                "$time ${teams[0]} VS ${teams[1]}"
            } else {
                "$time $league"
            }

            if (title.isNotEmpty()) {
                items.add(newLiveSearchResponse(
                    title,
                    // Puntiamo alla pagina che apre il modal dei server
                    "$mainUrl/en-vivo/", 
                    TvType.Live,
                    image
                ))
            }
        }
        
        return newHomePageResponse(listOf(HomePageList("Eventi SportzX Live", items)), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h3.vsc-modal-title")?.text() ?: "Diretta Live"

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
        
        // Estraiamo i link dei server (classe vsc-stream-link)
        val serverLinks = doc.select("a.vsc-stream-link").map { it.attr("href") }

        serverLinks.distinct().forEach { serverUrl ->
            val finalUrl = if (serverUrl.startsWith("/")) mainUrl + serverUrl else serverUrl
            
            try {
                // Fondamentale passare il referer corretto per i server come topstream
                val serverPage = app.get(finalUrl, referer = data, headers = headers).text
                val m3u8Regex = Regex("""(?:file|source|src|url)\s*[:=]\s*["'](https?.*?\.m3u8.*?)["']""")
                val streamUrl = m3u8Regex.find(serverPage)?.groupValues?.get(1)

                if (streamUrl != null) {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "Server " + finalUrl.substringAfter("://").substringBefore("/"),
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = finalUrl
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("SportzX", "Errore link $finalUrl")
            }
        }
        return true
    }
}
