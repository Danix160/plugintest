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
        "$mainUrl/category/commedia/" to "Commedia",
        "$mainUrl/category/fantascienza/" to "Fantascienza",
        "$mainUrl/category/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("li.movies, article.movies").mapNotNull { it.toSearchResult() }
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
        val posterUrl = selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("//")) "https:$it" else it 
        }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val pUrl = document.selectFirst(".post-thumbnail img")?.attr("src")
        val poster = if (pUrl?.startsWith("//") == true) "https:$pUrl" else pUrl
        val plot = document.selectFirst(".description p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Carichiamo la pagina principale del film
        val response = app.get(data, headers = mapOf("User-Agent" to clientUserAgent))
        val document = response.document

        // Cerchiamo tutti i possibili iframe o bottoni del player
        // Il tuo log mostra: ?trembed=0&trid=56450&trtype=1
        document.select("iframe, div[data-src], div[data-litespeed-src], .dooplay_player_option").forEach { element ->
            var src = element.attr("src")
                .ifEmpty { element.attr("data-src") }
                .ifEmpty { element.attr("data-litespeed-src") }
                .ifEmpty { element.attr("data-url") } // Aggiunto per i bottoni dooplay

            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"
            if (src.startsWith("/")) src = mainUrl + src

            Log.d("GuardaPlay", "Analisi sorgente: $src")

            // Se l'URL contiene "trembed", dobbiamo caricarlo per vedere cosa c'è dentro
            if (src.contains("trembed")) {
                try {
                    val innerPage = app.get(src, referer = data).document
                    val innerIframe = innerPage.selectFirst("iframe")?.attr("src")
                    if (innerIframe != null) {
                        val finalUrl = if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe
                        Log.d("GuardaPlay", "Iframe interno trovato: $finalUrl")
                        // Ora passiamo l'URL dell'iframe agli estrattori (Voe, Vidhide, ecc.)
                        loadExtractor(finalUrl, src, subtitleCallback, callback)
                    }
                    
                    // Cerchiamo anche m3u8 diretti nel codice dell'embed
                    val m3u8Regex = Regex("""https?[:\\]+[/\\\w\d\.-]+\.m3u8(?:\?v=[\d]+)?""")
                    m3u8Regex.findAll(innerPage.html()).forEach { match ->
                        callback.invoke(
                            newExtractorLink("GuardaPlay", "HD Player", match.value.replace("\\/", "/"), ExtractorLinkType.M3U8) {
                                this.quality = Qualities.P1080.value
                                this.referer = src
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("GuardaPlay", "Errore nell'embed: ${e.message}")
                }
            } else {
                // Se è già un link a un hoster noto, lo carichiamo direttamente
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
