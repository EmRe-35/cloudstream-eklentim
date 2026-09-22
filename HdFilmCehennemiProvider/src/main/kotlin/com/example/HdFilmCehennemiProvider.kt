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
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "*/*"
    )

    // 1. Ana Sayfa (Tüm Liste)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val res = app.get(url, headers = headers)
        val document = res.document
        val homePages = mutableListOf<HomePageList>()

        // Sitedeki tüm poster kartlarını (grid, slider, sidebar) yakala
        val allItems = document.select("a.poster, article, div.poster, div.movie-box, div.card, a[href*='/film/'], a[href*='/dizi/']").mapNotNull { element: Element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        if (allItems.isNotEmpty()) {
            homePages.add(HomePageList("Son Eklenenler", allItems))
        }

        return newHomePageResponse(homePages)
    }

    // 2. Arama
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl, headers = headers).document

        return document.select("a.poster, article, div.poster, div.movie-box, div.card, a[href*='/film/'], a[href*='/dizi/']").mapNotNull { element: Element ->
            element.toSearchResult()
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        var href = if (this.tagName() == "a") {
            this.attr("href")
        } else {
            this.selectFirst("a")?.attr("href")
        } ?: return null

        if (href.isBlank() || href == "#" || href.contains("/kategori/") || href.contains("/imdb/") || href.contains("/sayfa/")) return null
        if (!href.startsWith("http")) href = "$mainUrl$href"

        val titleEl = this.selectFirst("h2, h3, .title, a.title, strong, img, span.name")
        val rawTitle = if (titleEl != null && titleEl.tagName() == "img") titleEl.attr("alt") else titleEl?.text() ?: ""
        val title = rawTitle.trim()
        if (title.isEmpty()) return null

        val imgEl = this.selectFirst("img")
        var posterUrl: String? = null
        if (imgEl != null) {
            val dataSrc = imgEl.attr("data-src").trim()
            val dataLazySrc = imgEl.attr("data-lazy-src").trim()
            val src = imgEl.attr("src").trim()
            
            posterUrl = when {
                dataSrc.isNotEmpty() -> dataSrc
                dataLazySrc.isNotEmpty() -> dataLazySrc
                src.isNotEmpty() -> src
                else -> null
            }
        }

        if (posterUrl != null && !posterUrl.startsWith("http")) {
            posterUrl = "$mainUrl$posterUrl"
        }

        val isTvSeries = href.contains("/dizi/")
        val type = if (isTvSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    // 3. Detay Sayfası
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1, header h1")?.text()?.trim() ?: "İçerik"
        val imgEl = document.selectFirst("div.poster img, article img, img.cover")
        
        var poster: String? = null
        if (imgEl != null) {
            val dataSrc = imgEl.attr("data-src").trim()
            val src = imgEl.attr("src").trim()
            poster = if (dataSrc.isNotEmpty()) dataSrc else src
        }

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

    // 4. Video Bağlantılarını Çekme (Hem Static HTML Hem AJAX Taraması)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val document = res.document
        var found = false

        // A) Doğrudan iframe ve embed linklerini tara
        val elements = document.select("iframe[src], iframe[data-src], div[data-video], button[data-video], li[data-video]")
        for (el in elements) {
            var src = el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-video") }.trim()
            
            if (src.startsWith("//")) src = "https:$src"

            if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("google") && !src.contains("disqus")) {
                if (loadExtractor(src, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        // B) Eğer statik iframe bulunamadıysa Sitenin Player AJAX isteklerini tara
        if (!found) {
            val scripts = document.select("script")
            for (script in scripts) {
                val code = script.html()
                if (code.contains("eval") || code.contains("player") || code.contains("iframe")) {
                    // Script içindeki gizlenmiş video URL'lerini yakala
                    val regex = Regex("""(https?://[^\s"'<>]*(?:player|embed|video|v)[^\s"'<>]*)""")
                    val matches = regex.findAll(code)
                    for (match in matches) {
                        val videoUrl = match.value
                        if (loadExtractor(videoUrl, data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            }
        }

        return found
    }
}
