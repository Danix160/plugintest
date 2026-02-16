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

    // Lista host supportati per il filtraggio dei link
    private val supportedHosts = listOf(
        "voe", "chuckle-tube", "luluvdo", "lulustream", "vidhide", "rpmshare", "rpmplay",
        "mixdrop", "streamtape", "fastream", "filemoon", "wolfstream", "streamwish", "mivalyo"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/anime" to "Anime",
        "$mainUrl/category/film-animazione/" to "Film Animazione",
        "$mainUrl/category/serie-tv/" to "Serie TV",
    )

    private fun fixHostUrl(url: String): String {
        var fixedUrl = url.trim()
        
        // --- GESTIONE RPM (RPMPlay / RPMShare) ---
        if (fixedUrl.contains("rpmplay") || fixedUrl.contains("rpmshare")) {
            val id = if (fixedUrl.contains("#")) {
                fixedUrl.substringAfterLast("#")
            } else {
                fixedUrl.substringAfterLast("/")
            }
            // Forza il dominio principale e il path /e/ per l'estrattore
            return "https://rpmshare.club/e/$id"
        }

        // --- ALTRI FIX HOST ---
        return fixedUrl
            .replace("chuckle-tube.com", "voe.sx")
            .replace("luluvdo.com", "lulustream.com")
            .replace("ryderjet.com", "vidhide.com")
            .replace("mivalyo.com", "vidhide.com")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        
        val items = document.select("article").mapNotNull { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val href = titleHeader.attr("href")
            val posterUrl = article.selectFirst("img")?.let { 
                it.attr("src") ?: it.attr("data-src") 
            } ?: searchPlaceholderLogo

            newTvSeriesSearchResponse(titleHeader.text(), href, TvType.TvSeries) {
                this.posterUrl = posterUrl
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

            newTvSeriesSearchResponse(titleHeader.text(), href, TvType.TvSeries) {
                this.posterUrl = searchPlaceholderLogo
            }
        }.filterNotNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
        
        val poster = document.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img")?.attr("src")
            ?: searchPlaceholderLogo

        val entryContent = document.selectFirst("div.entry-content")
        val episodes = mutableListOf<Episode>()
        
        val rows = entryContent?.select("p, li, tr, div") ?: listOf()
        var absoluteEpCounter = 1

        rows.forEach { row ->
            val links = row.select("a").filter { a -> 
                val href = a.attr("href")
                supportedHosts.any { host -> href.contains(host) }
            }.map { it.attr("href") }.distinct()

            if (links.isNotEmpty()) {
                val text = row.text().trim()
                if (text.contains(Regex("(?i)sigla|intro|trailer"))) return@forEach

                val matchSE = Regex("""(\d+)[×x](\d+)""").find(text)
                val s = matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val e = matchSE?.groupValues?.get(2)?.toIntOrNull() ?: absoluteEpCounter

                val dataUrls = links.joinToString("###")
                
                // --- REGEX DI PULIZIA NOME EPISODIO CON TUTTI GLI HOST ---
                val hostCleaner = Regex("(?i)VOE|Lulu|RPM|Vidhide|Mivalyo|Mixdrop|Streamtape|Fastream|Filemoon|Wolfstream|Streamwish|Streaming|Link| -")
                var epName = text.split(hostCleaner).first().trim()
                
                // Rimuove scritte residue (es. date o "Aggiornato il")
                epName = epName.replace(Regex("(?i)Aggiornato.*|\\d{2}/\\d{2}/\\d{4}"), "").trim()

                if (epName.isEmpty() || epName.length < 2) epName = "Episodio $e"

                episodes.add(newEpisode(dataUrls) {
                    this.name = epName
                    this.season = s
                    this.episode = e
                })

                if (matchSE == null) absoluteEpCounter++
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split("###").forEach { url ->
            val fixedUrl = fixHostUrl(url)
            loadExtractor(fixedUrl, subtitleCallback, callback)
        }
        return true
    }
}
