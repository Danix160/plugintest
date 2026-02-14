package com.sportzx

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.fasterxml.jackson.annotation.JsonProperty

class SportzxProvider : MainAPI() {
    override var mainUrl = "https://sportzx.cc" 
    override var name = "SportzX"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Live)

    // Header molto completi per simulare un browser reale
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        Log.d("SportzX", "Inizio recupero dati...")
        
        // 1. Proviamo a scaricare la pagina
        val res = app.get("$mainUrl/sportzx-live/", headers = commonHeaders)
        val doc = res.document
        val items = mutableListOf<SearchResponse>()

        // 2. Se la pagina è vuota (come dice il log), cerchiamo i dati dentro gli script
        // Molti siti Elementor salvano i dati in un oggetto JSON chiamato 'vsc_data' o simili
        val scriptData = doc.select("script").filter { it.data().contains("vsc-card") || it.data().contains("teams") }
        
        // Se non troviamo card nell'HTML, usiamo un selettore di emergenza molto largo
        val cards = doc.select("div.vsc-card, .elementor-widget-container h3")
        Log.d("SportzX", "Analisi HTML: trovati ${cards.size} elementi potenziali")

        if (cards.isEmpty()) {
            // Tentativo disperato: se il sito è protetto, cerchiamo qualsiasi link che sembri un match
            doc.select("a").forEach { 
                val text = it.text()
                if (text.contains("vs", ignoreCase = true) || it.attr("href").contains("live")) {
                    items.add(newLiveSearchResponse(text, it.attr("href"), TvType.Live))
                }
            }
        } else {
            cards.forEach { card ->
                val teams = card.select(".vsc-team-name, h3").map { it.text().trim() }
                val time = card.selectFirst(".vsc-time")?.text() ?: ""
                val imageUrl = card.selectFirst("img")?.attr("src")

                val title = if (teams.isNotEmpty()) teams.joinToString(" VS ") else "Evento Live"
                
                items.add(newLiveSearchResponse(
                    name = "$time $title".trim(),
                    url = "$mainUrl/en-vivo/", 
                    type = TvType.Live
                ).apply { this.posterUrl = imageUrl })
            }
        }

        // Se ancora non c'è nulla, carichiamo i link diretti dalla pagina principale
        if (items.isEmpty()) {
            val home = app.get(mainUrl, headers = commonHeaders).document
            home.select("a[href*='live'], a[href*='streaming']").forEach {
                items.add(newLiveSearchResponse(it.text(), it.attr("href"), TvType.Live))
            }
        }

        Log.d("SportzX", "Totale item pronti: ${items.size}")
        return newHomePageResponse(listOf(HomePageList("Eventi in Diretta", items)), false)
    }

    override suspend fun load(url: String): LoadResponse {
        // Carichiamo la pagina e forziamo il titolo
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
        val res = app.get(data, headers = commonHeaders)
        val doc = res.document
        
        // Cerchiamo i link vsc-stream-link o iframe
        val links = doc.select("a.vsc-stream-link, iframe").map { 
            if (it.tagName() == "a") it.attr("href") else it.attr("src") 
        }

        links.distinct().filter { it.isNotBlank() }.forEach { link ->
            val finalUrl = if (link.startsWith("/")) mainUrl + link else link
            try {
                val text = app.get(finalUrl, referer = data, headers = commonHeaders).text
                val m3u8 = Regex("""["'](https?.*?\.m3u8.*?)["']""").find(text)?.groupValues?.get(1)
                
                if (m3u8 != null) {
                    callback(
                        newExtractorLink(this.name, "Server HD", m3u8, finalUrl, Qualities.P1080.value, true)
                    )
                }
            } catch (e: Exception) { }
        }
        return true
    }
}
