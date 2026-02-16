package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup

// --- ESTRATTORE PERSONALIZZATO PER RPMSHARE / RPMPLAY ---
class RpmShare : ExtractorApi() {
    override val name = "RPMShare"
    override val mainUrl = "https://rpmplay.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        // Estraiamo l'ID dall'hash (es. zth3k da #zth3k)
        val id = url.substringAfter("#")
        if (id == url) return null

        // Endpoint API per ottenere il file sorgente
        val apiUrl = "https://rpmplay.xyz/api/source/$id"
        
        // Simulazione della chiamata XHR che farebbe il browser
        val response = app.post(
            apiUrl,
            data = mapOf("r" to "", "d" to "rpmplay.xyz"),
            headers = mapOf(
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            )
        )

        return try {
            // Parsing della risposta JSON: {"data": [{"file": "...", "label": "1080p"}]}
            response.parsed<RpmResponse>().data.map { stream ->
                ExtractorLink(
                    source = name,
                    name = name,
                    url = stream.file,
                    referer = url,
                    quality = getQualityFromName(stream.label),
                    isM3u8 = stream.file.contains("m3u8")
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    data class RpmResponse(val data: List<RpmStream>)
    data class RpmStream(val file: String, val label: String)
}

// --- PROVIDER PRINCIPALE TOONITALIA ---
class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "ToonItalia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    private val searchPlaceholderLogo = "https://toonitalia.xyz/wp-content/uploads/2023/11/toonitalia-logo-1.png"

    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    private val supportedHosts = listOf(
        "voe", "chuckle-tube", "luluvdo", "lulustream", "vidhide", "rpmshare", "rpmplay", "streamup",
        "mixdrop", "streamtape", "fastream", "filemoon", "wolfstream", "streamwish"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/anime" to "Anime",
        "$mainUrl/category/film-animazione/" to "Film Animazione",
        "$mainUrl/category/serie-tv/" to "Serie TV",
    )

    private fun fixHostUrl(url: String): String {
        return url
            .replace("chuckle-tube.com", "voe.sx")
            .replace("luluvdo.com", "lulustream.com")
            .replace("luluvideo.com", "lulustream.com")
            .replace("toonitalia.rpmplay.xyz/", "rpmplay.xyz/")
            .replace("streamup.ws", "streamwish.to")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page/"
        val document = app.get(url, headers = commonHeaders, timeout = 10).document
        
        val items = document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val href = titleHeader.attr("href")
            val posterUrl = article.selectFirst("img")?.attr("src") 
                ?: article.selectFirst("img")?.attr("data-src")
                ?: searchPlaceholderLogo

            newTvSeriesSearchResponse(titleHeader.text(), href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = commonHeaders
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document
        
        return document.select("article").amap { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@amap null
            val title = titleHeader.text()
            val href = titleHeader.attr("href")

            val innerPage = app.get(href, headers = commonHeaders).document
            val posterUrl = innerPage.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img, .entry-content img")?.attr("src")
                ?: innerPage.selectFirst("meta[property=\"og:image\"]")?.attr("content")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl ?: searchPlaceholderLogo
                this.posterHeaders = commonHeaders
            }
        }.filterNotNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
        
        val poster = document.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img, .entry-content img")?.attr("src")
            ?: searchPlaceholderLogo

        val entryContent = document.selectFirst("div.entry-content")
        val fullText = entryContent?.text() ?: ""

        val categories = document.select(".entry-categories-inner a").map { it.text().lowercase() }
        val isMovie = categories.any { it.contains("film animazione") || it == "film" }
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        val tramaElement = document.selectFirst("h3:contains(Trama:), p:contains(Trama:), b:contains(Trama:)")
        var plot = if (tramaElement != null) {
            val nextText = tramaElement.nextSibling()?.toString()?.replace(Regex("<[^>]*>"), "")?.trim()
            if (!nextText.isNullOrBlank()) nextText else tramaElement.parent()?.text()?.substringAfter("Trama:")?.trim()
        } else {
            document.select("div.entry-content p")
                .map { it.text() }
                .firstOrNull { 
                    it.length > 60 && 
                    !it.contains(Regex("(?i)Titolo originale|Paese di origine|Stato Opera|Aggiornamento")) 
                }
        }

        val stopWords = listOf("(?i)Fonte:", "(?i)Animeclick", "(?i)\\bLink\\b")
        stopWords.forEach { word -> plot = plot?.split(Regex(word), 2)?.first()?.trim() }

        val duration = Regex("""(\d+)\s?min""").find(fullText)?.groupValues?.get(1)?.toIntOrNull()
        val year = Regex("""\b(19\d{2}|20[0-2]\d)\b""").find(fullText)?.groupValues?.get(1)?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val lines = entryContent?.html()?.split(Regex("<br\\s*/?>|</p>|</div>|<li>|\\n")) ?: listOf()
        var absoluteEpCounter = 1

        lines.forEach { line ->
            val docLine = Jsoup.parseBodyFragment(line)
            val text = docLine.text().trim()
            
            val validLinks = docLine.select("a").filter { a -> 
                val href = a.attr("href")
                href.startsWith("http") && 
                !href.contains("toonitalia.xyz") && 
                supportedHosts.any { host -> href.contains(host) }
            }.map { it.attr("href") }.distinct()

            if (validLinks.isNotEmpty()) {
                val isTrailerRow = text.contains(Regex("(?i)sigla|intro|trailer"))
                val matchSE = Regex("""(\d+)[×x](\d+)""").find(text)

                val s = if (isTrailerRow) 0 else if (isMovie) null else (matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1)
                val e = if (isTrailerRow) 0 else if (isMovie) null else (matchSE?.groupValues?.get(2)?.toIntOrNull() ?: absoluteEpCounter)

                val dataUrls = validLinks.joinToString("###")
                
                var epName = text.split(Regex("(?i)VOE|Lulu|Streaming|Vidhide|Mixdrop|RPMShare|VIDHIDE|STREAMUP|Link| -")).first().trim()
                if (epName.isEmpty() || epName.length < 2) {
                    epName = if (isMovie) "Film" else "Episodio $absoluteEpCounter"
                }

                episodes.add(newEpisode(dataUrls) {
                    this.name = epName
                    this.season = s
                    this.episode = e
                    this.posterUrl = poster
                })

                if (!isMovie && !isTrailerRow && matchSE == null) absoluteEpCounter++ 
            }
        }

        val finalEpisodes = episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, finalEpisodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.posterHeaders = commonHeaders
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, finalEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split("###").forEach { url ->
            val fixedUrl = fixHostUrl(url)
            
            // Gestione speciale per RPMShare/RPMPLAY
            if (fixedUrl.contains("rpmplay.xyz")) {
                RpmShare().getUrl(fixedUrl, "$mainUrl/")?.forEach(callback)
            } else {
                // Gestione standard per tutti gli altri host
                loadExtractor(fixedUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
