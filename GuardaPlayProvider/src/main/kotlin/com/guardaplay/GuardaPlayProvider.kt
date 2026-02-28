package com.guardaplay

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    private val clientUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/commedia/" to "Commedia"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("li.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("li.movies, article.movies").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        
        // FIX POSTER: Forza protocollo HTTPS per evitare FileNotFoundException
        val posterUrl = selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("//")) "https:$it" else it 
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val document = response.document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            val pUrl = document.selectFirst(".post-thumbnail img")?.attr("src")
            this.posterUrl = if (pUrl?.startsWith("//") == true) "https:$pUrl" else pUrl
            this.plot = document.selectFirst(".description p")?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("GuardaPlay", "Avvio caricamento link per: $data")
        val response = app.get(data)
        val document = response.document
        val html = response.text

        // 1. RICERCA NEGLI IFRAME CON LOGGING
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.ifEmpty { iframe.attr("data-litespeed-src") }
            if (src.startsWith("//")) src = "https:$src"

            if (src.contains("loadm.cam") || src.contains("pancast.net")) {
                Log.d("GuardaPlay", "Trovato iframe critico: $src")
                try {
                    val frameRes = app.get(
                        src, 
                        referer = data, 
                        headers = mapOf("User-Agent" to clientUserAgent)
                    ).text

                    // Logghiamo una parte del contenuto per debug (primi 500 caratteri)
                    Log.d("GuardaPlay", "Contenuto Frame (estratto): ${frameRes.take(500)}")

                    // Regex potenziata per link m3u8 codificati o normali
                    val m3u8Regex = Regex("""https?[:\\]+[/\\\w\d\.-]+\.m3u8(?:\?v=[\d]+)?""")
                    val m3u8Raw = m3u8Regex.find(frameRes)?.value?.replace("\\/", "/")

                    if (m3u8Raw != null) {
                        Log.d("GuardaPlay", "M3U8 TROVATO: $m3u8Raw")
                        callback.invoke(
                            newExtractorLink(
                                source = "GuardaPlay",
                                name = "Player HD",
                                url = m3u8Raw,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = "https://loadm.cam/"
                            }
                        )
                    } else {
                        Log.w("GuardaPlay", "Nessun m3u8 trovato nel frame!")
                    }
                } catch (e: Exception) {
                    Log.e("GuardaPlay", "Errore durante l'estrazione dal frame: ${e.message}")
                }
            }
        }

        // 2. PIANO B: ESTRATTORI UNIVERSALI
        val hosterRegex = Regex("""https?://(?:vidhide|voe|streamwish|mixdrop|filemoon|ryderjet|vood|embedwish|wolfstream|dood|streamtape)[^\s"'<>\\\/]+""")
        hosterRegex.findAll(html).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")
            Log.d("GuardaPlay", "Trovato hoster esterno: $cleanUrl")
            loadExtractor(cleanUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
