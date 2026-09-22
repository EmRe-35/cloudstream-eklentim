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

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // 1. Ana Sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val document = app.get(url, headers = headers).document

        val allItems = document.select("a.poster, article, div.poster, div.movie-box, div.card, a[href*='/film/'], a[href*='/dizi/']").mapNotNull { element: Element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(listOf(HomePageList("Son Eklenenler", allItems)))
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

    // 3. Detay Sayfası
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

    // 4. Doğrudan Player Domain Taraması ve Redirect Takibi
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val pageRes = app.get(data, headers = headers)
        val doc = pageRes.document

        val linksToExtract = mutableSetOf<String>()

        // Adım 1: Sitedeki tüm video/embed içeren öznitelikleri topla
        doc.select("[data-src], [data-video], [data-url], iframe, source").forEach { el ->
            val candidate = el.attr("data-src")
                .ifEmpty { el.attr("data-video") }
                .ifEmpty { el.attr("data-url") }
                .ifEmpty { el.attr("src") }
            if (candidate.isNotEmpty()) linksToExtract.add(candidate)
        }

        // Adım 2: Alternatif kaynak butonlarını/tab'larını gez
        doc.select("nav.nav-tabs a, div.alternative-links a, button[data-video]").forEach { btn ->
            val url = btn.attr("data-video").ifEmpty { btn.attr("href") }
            if (url.isNotEmpty() && !url.startsWith("#")) linksToExtract.add(url)
        }

        // Adım 3: Linkleri dönüştür ve Extractor'a gönder
        for (rawUrl in linksToExtract) {
            var cleanUrl = rawUrl.trim()
            if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
            if (!cleanUrl.startsWith("http")) cleanUrl = "$mainUrl$cleanUrl"

            if (cleanUrl.contains("facebook") || cleanUrl.contains("google") || cleanUrl.contains("twitter")) continue

            // Yönlendirme (Redirect) takibi yaparak son embed adresine ulaş
            try {
                val headReq = app.get(cleanUrl, headers = headers, allowRedirects = true)
                val finalUrl = headReq.url

                // Hem ilk URL'i hem de yönlendirilen son URL'i dene
                if (loadExtractor(finalUrl, data, subtitleCallback, callback)) {
                    found = true
                } else if (loadExtractor(cleanUrl, data, subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {
                if (loadExtractor(cleanUrl, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        return found
    }
}
