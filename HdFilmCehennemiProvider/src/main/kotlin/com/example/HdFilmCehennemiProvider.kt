package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HdFilmCehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val document = app.get(url, headers = headers).document

        val allItems = document.select("a.poster, article, div.poster, div.movie-box, div.card, a[href*='/film/'], a[href*='/dizi/']").mapNotNull { element: Element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("Son Eklenenler", allItems)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl, headers = headers).document

        return document.select("a.poster, article, div.poster, div.movie-box, div.card, a[href*='/film/'], a[href*='/dizi/']").mapNotNull { element: Element ->
            element.toSearchResult()
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        var href = if (this.tagName() == "a") this.attr("href") else this.selectFirst("a")?.attr("href") ?: return null

        if (href.isBlank() || href == "#" || href.contains("/kategori/") || href.contains("/imdb/") || href.contains("/sayfa/")) return null
        if (!href.startsWith("http")) href = "$mainUrl$href"

        val titleEl = this.selectFirst("h2, h3, .title, a.title, strong, img, span.name")
        val rawTitle = if (titleEl != null && titleEl.tagName() == "img") titleEl.attr("alt") else titleEl?.text() ?: ""
        val title = rawTitle.trim()
        if (title.isEmpty()) return null

        val imgEl = this.selectFirst("img")
        var posterUrl = imgEl?.attr("data-src")?.ifEmpty { imgEl.attr("src") }
        if (posterUrl != null && !posterUrl.startsWith("http")) posterUrl = "$mainUrl$posterUrl"

        val isTvSeries = href.contains("/dizi/")
        val type = if (isTvSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1, header h1")?.text()?.trim() ?: "İçerik"
        val imgEl = document.selectFirst("div.poster img, article img, img.cover")
        
        val poster = imgEl?.attr("data-src")?.ifEmpty { imgEl.attr("src") }
        val plot = document.selectFirst("div.post-content, p.story, div.overview, div.description")?.text()?.trim()
        val isTvSeries = url.contains("/dizi/")

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.episode-list a, ul.episodes a, div.seasons-list a, a[href*='bolum']").forEach { ep ->
                var epHref = ep.attr("href").trim()
                if (epHref.isNotEmpty()) {
                    if (!epHref.startsWith("http")) epHref = "$mainUrl$epHref"
                    episodes.add(newEpisode(epHref) { this.name = ep.text().trim() })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // TEŞHİS VE LOGLAMA FONKSİYONU
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("HDFilmCehennemi LOG: Taranan Film Sayfası -> $data")
        var found = false

        try {
            val response = app.get(data, headers = headers)
            println("HDFilmCehennemi LOG: HTTP Yanıt Kodu -> ${response.code}")

            val document = response.document
            val iframes = document.select("iframe, [data-src], [data-video]")
            println("HDFilmCehennemi LOG: Bulunan İframe/Element Sayısı -> ${iframes.size}")

            for (el in iframes) {
                val src = el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-video") }
                println("HDFilmCehennemi LOG: Yakalanan Adres -> $src")

                if (src.isNotEmpty()) {
                    var fullUrl = src
                    if (fullUrl.startsWith("//")) fullUrl = "https:$fullUrl"
                    if (!fullUrl.startsWith("http")) fullUrl = "$mainUrl$fullUrl"

                    val isExtracted = loadExtractor(fullUrl, data, subtitleCallback, callback)
                    println("HDFilmCehennemi LOG: Extractor Sonucu ($fullUrl) -> $isExtracted")
                    if (isExtracted) found = true
                }
            }
        } catch (e: Exception) {
            println("HDFilmCehennemi LOG HATA: ${e.message}")
        }

        return found
    }
}
