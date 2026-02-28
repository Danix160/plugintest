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
        // Usiamo solo "article.movies" per evitare i doppioni causati dal tag "li"
        val home = document.select("article.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.movies").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        
        // Estrazione poster con gestione corretta del protocollo //
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
        var foundAny = false

        // 1. DooPlay Player Options (AJAX)
        document.select(".dooplay_player_option, li[id^=player-option-]").forEach { option ->
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")

            if (post.isNotBlank()) {
                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text

                // Cerchiamo l'URL dell'iframe o il link diretto nel testo della risposta
                val iframeUrl = Regex("""<iframe.*?src=["'](.*?)["']""").find(response)?.groupValues?.get(1)
                    ?: Regex("""https?://[^\s"']+""").find(response)?.value

                iframeUrl?.let { 
                    if (processFinalUrl(it, data, callback)) foundAny = true 
                }
            }
        }

        // 2. Fallback per iframe statici
        if (!foundAny) {
            document.select("iframe").forEach { 
                val src = it.attr("src")
                if (src.contains("http") && !src.contains("facebook") && !src.contains("google")) {
                    if (processFinalUrl(src, data, callback)) foundAny = true
                }
            }
        }

        return foundAny
    }

    private suspend fun processFinalUrl(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val cleanUrl = url.replace("\\/", "/").let { if (it.startsWith("//")) "https:$it" else it }
        
        return when {
            // Gestione server m3u8 personalizzati (LoadM, Pancast, etc.)
            cleanUrl.contains("loadm.cam") || cleanUrl.contains("pancast") || cleanUrl.contains(".m3u8") -> {
                val streamUrl = if (cleanUrl.contains(".m3u8")) {
                    cleanUrl
                } else {
                    val pageText = app.get(cleanUrl, referer = referer).text
                    Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(pageText)?.groupValues?.get(1)
                }

                streamUrl?.let {
                    callback.invoke(
                        newExtractorLink(
                            "GuardaPlay",
                            "Server HD",
                            it,
                            ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = cleanUrl
                        }
                    )
                    true
                } ?: false
            }
            else -> {
                // Carica estrattori automatici (Voe, Vidhide, ecc.)
                loadExtractor(cleanUrl, referer, { }, callback)
            }
        }
    }
}
