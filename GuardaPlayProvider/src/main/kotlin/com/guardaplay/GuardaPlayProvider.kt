package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // User-Agent dal tuo cURL per bypassare i controlli di sicurezza
    private val clientUserAgent = "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

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

    // --- FUNZIONE DI RICERCA AGGIUNTA ---
    override suspend fun search(query: String): List<SearchResponse> {
        // La ricerca su questo sito usa il parametro ?s=
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        // Cerca i film nei risultati (solitamente hanno la classe .movies o sono dentro articoli)
        return document.select("li.movies, article.movies").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("//")) "https:$it" else it 
        }
        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = document.selectFirst(".post-thumbnail img")?.attr("src")
            this.plot = document.selectFirst(".description p")?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. ESTRAZIONE DAL PLAYER INTERNO (LOADM / PANCAST)
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.ifEmpty { iframe.attr("data-litespeed-src") }
            if (src.startsWith("//")) src = "https:$src"

            if (src.contains("loadm.cam") || src.contains("pancast.net")) {
                try {
                    val frameRes = app.get(
                        src, 
                        referer = data, 
                        headers = mapOf("User-Agent" to clientUserAgent)
                    ).text

                    val m3u8Regex = Regex("""https?://[^\s"'<>]+?\.m3u8(?:\?v=[\d]+)?""")
                    val m3u8Raw = m3u8Regex.find(frameRes)?.value?.replace("\\/", "/")

                    if (m3u8Raw != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = "GuardaPlay",
                                name = "Player HD (HLS)",
                                url = m3u8Raw,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = "https://loadm.cam/"
                            }
                        )
                    }
                } catch (e: Exception) { }
            }
        }

        // 2. PIANO B: ESTRATTORI STANDARD (Vidhide, Voe, Streamwish, ecc.)
        val html = document.html()
        val hosterRegex = Regex("""https?://(?:vidhide|voe|streamwish|mixdrop|filemoon|ryderjet|vood|embedwish|wolfstream|dood|streamtape)[^\s"'<>\\\/]+""")
        hosterRegex.findAll(html).forEach { match ->
            try {
                loadExtractor(match.value.replace("\\/", "/"), data, subtitleCallback, callback)
            } catch (e: Exception) { }
        }

        return true
    }
}
