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

    private val liveUrl = "$mainUrl/sportzx-live/"

    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Log per vedere se la funzione viene chiamata
        Log.d("SportzX", "Caricamento Main Page da: $liveUrl")
        
        val res = app.get(liveUrl, headers = commonHeaders)
        val doc = res.document
        val items = mutableListOf<SearchResponse>()

        // Selettore specifico per la struttura vsc-card
        val cards = doc.select("div.vsc-card")
        Log.d("SportzX", "Trovate ${cards.size} card nel documento")

        cards.forEach { card ->
            val teams = card.select("div.vsc-team-name").map { it.text().trim() }
            val time = card.selectFirst("div.vsc-time")?.text()?.trim() ?: ""
            val league = card.selectFirst("span.vsc-league-text")?.text()?.trim() ?: ""
            val imageUrl = card.selectFirst("img.vsc-league-logo")?.attr("src")

            val title = if (teams.size >= 2) {
                "$time ${teams[0]} VS ${teams[1]}"
            } else if (league.isNotEmpty()) {
                "$time $league"
            } else {
                "Evento Live"
            }

            // Creazione sicura della risposta
            val searchRes = newLiveSearchResponse(
                name = title,
                url = "$mainUrl/en-vivo/", // Pagina di destinazione generica
                type = TvType.Live
            ).apply { 
                this.posterUrl = imageUrl 
            }
            items.add(searchRes)
        }
        
        if (items.isEmpty()) Log.w("SportzX", "Nessun item trovato! Controlla i selettori.")
        
        return newHomePageResponse(listOf(HomePageList("Eventi SportzX Live", items)), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document
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
        Log.d("SportzX", "Caricamento link da: $data")
        val doc = app.get(data, headers = commonHeaders).document
        
        // Cerca i link ai server nel modal (vsc-stream-link)
        val serverLinks = doc.select("a.vsc-stream-link").map { it.attr("href") }
        Log.d("SportzX", "Trovati ${serverLinks.size} link server")

        serverLinks.distinct().forEach { serverUrl ->
            val finalUrl = if (serverUrl.startsWith("/")) mainUrl + serverUrl else serverUrl
            
            try {
                // Ogni server ha bisogno del suo referer specifico per sbloccare l'm3u8
                val serverPage = app.get(finalUrl, referer = data, headers = commonHeaders).text
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
                Log.e("SportzX", "Errore nel server $finalUrl: ${e.message}")
            }
        }
        return true
    }
}
