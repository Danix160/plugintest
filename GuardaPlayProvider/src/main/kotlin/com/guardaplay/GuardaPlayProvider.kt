package com.guardaplay // Cambia con il pacchetto del tuo plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.bar"
    override var name = "GuardaPlay"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // 1. HOME PAGE: Carica i film dalla lista principale
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = document.select("ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse("Ultimi Film", home)
    }

    // 2. RICERCA: Basata sul file cerca.txt che hai inviato
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("ul.post-lst li.movies").mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. CARICAMENTO DETTAGLI: Estrae trama e info dalla pagina del film
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("div.post-poster img")?.attr("src")
        val plot = document.selectFirst("div.entry-content p")?.text()
        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // 4. ESTRAZIONE LINK: Trova gli iframe dei video
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Cerca i link nei vari server/tab del player
        document.select(".player-video iframe").forEach { iframe ->
            var link = iframe.attr("src")
            
            // Se il link è relativo, aggiungi il dominio
            if (link.startsWith("/")) link = mainUrl + link
            
            // Carica l'estrattore appropriato (Voe, Mixdrop, Supervideo, ecc.)
            loadExtractor(link, data, subtitleCallback, callback)
        }
        return true
    }

    // FUNZIONE HELPER: Converte l'HTML di un singolo elemento della lista in un oggetto SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title")?.text() ?: return null
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("//")) "https:$it" else it 
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}
