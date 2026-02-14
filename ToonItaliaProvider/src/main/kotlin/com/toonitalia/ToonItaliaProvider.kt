package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup

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

    // Aggiunto rpmplay alla lista degli host supportati
    private val supportedHosts = listOf(
        "voe", "chuckle-tube", "luluvdo", "lulustream", "vidhide", "rpmshare", "rpmplay",
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
            .replace("toonitalia.rpmplay.xyz/#", "rpmshare.club/v/") // Esempio di fix per RPM
            .replace("luluvdo.com", "lulustream.com")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        val items = document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            newTvSeriesSearchResponse(titleHeader.text(), titleHeader.attr("href"), TvType.TvSeries) {
                this.posterUrl = article.selectFirst("img")?.attr("src") ?: searchPlaceholderLogo
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document
        return document.select("article").amap { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@amap null
            val href = titleHeader.attr("href")
            val innerPage = app.get(href, headers = commonHeaders).document
            newTvSeriesSearchResponse(titleHeader.text(), href, TvType.TvSeries) {
                this.posterUrl = innerPage.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img")?.attr("src")
            }
        }.filterNotNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        val title = document.selectFirst("h1.entry-title")?.text()?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
        val poster = document.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img")?.attr("src") ?: searchPlaceholderLogo
        val entryContent = document.selectFirst("div.entry-content")

        // --- LOGICA TRAMA ---
        val tramaElement = document.selectFirst("h3:contains(Trama:), p:contains(Trama:), b:contains(Trama:)")
        var plot = if (tramaElement != null) {
            tramaElement.nextSibling()?.toString()?.replace(Regex("<[^>]*>"), "")?.trim()
                ?: tramaElement.parent()?.text()?.substringAfter("Trama:")?.trim()
        } else {
            document.select("div.entry-content p").map { it.text() }.firstOrNull { it.length > 60 && !it.contains("Titolo") }
        }
        listOf("(?i)Fonte:", "(?i)Animeclick", "(?i)\\bLink\\b").forEach { plot = plot?.split(Regex(it), 2)?.first()?.trim() }

        // --- LOGICA EPISODI (MULTI-SOURCE) ---
        val episodes = mutableListOf<Episode>()
        val isMovieUrl = url.contains("film") || url.contains("film-animazione")
        
        // Dividiamo il contenuto per righe (br o p)
        val lines = entryContent?.html()?.split(Regex("<br\\s*/?>|</p>")) ?: listOf()
        var absoluteEpCounter = 1

        lines.forEach { line ->
            val docLine = Jsoup.parseBodyFragment(line)
            val text = docLine.text().trim()
            
            // Troviamo TUTTI i link validi in questa riga
            val linksInLine = docLine.select("a").mapNotNull { a ->
                val href = a.attr("href")
                if (href.startsWith("http") && supportedHosts.any { href.contains(it) }) href else null
            }.distinct()

            if (linksInLine.isNotEmpty()) {
                val matchSE = Regex("""(\d+)[×x](\d+)""").find(text)
                val s = if (isMovieUrl) null else (matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1)
                val e = if (isMovieUrl) null else (matchSE?.groupValues?.get(2)?.toIntOrNull() ?: absoluteEpCounter)

                // Uniamo tutti i mirror con il separatore ###
                val dataUrls = linksInLine.joinToString("###")
                
                var epName = text.split(Regex("(?i)VOE|RPMShare|Lulu|Mixdrop|Streamtape|Link| -")).first().trim()
                if (epName.isEmpty() || epName.length < 2) epName = if (isMovieUrl) "Film" else "Episodio $absoluteEpCounter"

                episodes.add(newEpisode(dataUrls) {
                    this.name = epName
                    this.season = s
                    this.episode = e
                })
                if (!isMovieUrl && matchSE == null) absoluteEpCounter++
            }
        }

        val tvType = if (isMovieUrl) TvType.Movie else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, tvType, episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Divide i mirror salvati e li carica uno per uno
        data.split("###").forEach { url ->
            loadExtractor(fixHostUrl(url), subtitleCallback, callback)
        }
        return true
    }
}
