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

    private val clientUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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
        val document = app.get(data).document

        // Estrazione Opzioni Player (DooPlay style)
        document.select(".dooplay_player_option").forEach { option ->
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")

            if (post.isNotBlank()) {
                val res = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).document
                
                val src = res.selectFirst("iframe")?.attr("src") ?: res.text().let {
                    // Cerca URL nel testo se non c'è iframe (a volte è codificato in JSON)
                    Regex("""https?://[^\s"']+""").find(it)?.value
                }

                src?.let { processFinalUrl(it, data, callback) }
            }
        }

        // Fallback: cerca iframe standard
        document.select("iframe").forEach { 
            val src = it.attr("src")
            if (src.isNotBlank() && !src.contains("google")) {
                processFinalUrl(src, data, callback)
            }
        }

        return true
    }

    private suspend fun processFinalUrl(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        var cleanUrl = if (url.startsWith("//")) "https:$url" else url
        Log.d("GuardaPlay", "Analisi URL: $cleanUrl")

        if (cleanUrl.contains("loadm.cam") || cleanUrl.contains("pancast.net")) {
            // LoadM richiede spesso un trucco: il file video è caricato via AJAX o ha lo stesso ID dell'hash
            val pageText = app.get(cleanUrl, referer = referer).text
            
            // Proviamo a cercare pattern m3u8 o variabili file/sources
            val videoRegex = Regex("""(file|source|src)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            val match = videoRegex.find(pageText)
            
            if (match != null) {
                callback.invoke(
                    newExtractorLink("GuardaPlay", "Server HD", match.groupValues[2], cleanUrl, Qualities.P1080.value, true)
                )
            } else {
                // Se non troviamo nulla, proviamo a estrarre tramite l'ID nell'URL
                val id = cleanUrl.split("#").lastOrNull()
                if (id != null && id.length > 3) {
                    // Molti di questi server usano un'API interna tipo /api/source/ID
                    Log.d("GuardaPlay", "Tentativo estrazione ID: $id")
                }
            }
        } else {
            loadExtractor(cleanUrl, referer, { }, callback)
        }
    }
}
