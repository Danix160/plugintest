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

        // 1. Gestione Server tramite DooPlay Ajax
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
                    Regex("""https?://[^\s"']+""").find(it)?.value
                }

                src?.let { url ->
                    processFinalUrl(url, data, callback)
                }
            }
        }

        // 2. Fallback per iframe diretti nel corpo della pagina
        document.select("iframe").forEach { 
            val src = it.attr("src")
            if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook")) {
                processFinalUrl(src, data, callback)
            }
        }

        return true
    }

    private suspend fun processFinalUrl(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val cleanUrl = if (url.startsWith("//")) "https:$url" else url
        
        if (cleanUrl.contains(".m3u8") || cleanUrl.contains("loadm.cam") || cleanUrl.contains("pancast")) {
            val finalUrl = if (cleanUrl.contains(".m3u8")) {
                cleanUrl
            } else {
                val response = app.get(cleanUrl, referer = referer).text
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(response)?.groupValues?.get(1)
            }

            finalUrl?.let { link ->
                val type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                
                callback.invoke(
                    newExtractorLink(
                        "GuardaPlay",
                        "Server HD",
                        link.replace("\\/", "/"),
                        type
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = cleanUrl
                    }
                )
            }
        } else {
            // Usa gli estrattori di sistema per Voe, Vidhide, ecc.
            loadExtractor(cleanUrl, referer, { }, callback)
        }
    }
}
