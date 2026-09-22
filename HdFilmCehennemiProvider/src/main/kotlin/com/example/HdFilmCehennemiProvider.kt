package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
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

    private val cfInterceptor = CloudflareKiller()

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/",
        "Accept-Language" to "tr-TR,tr;q=0.9"
    )

    private suspend fun getWithCf(url: String): NiceResponse {
        return app.get(
            url = url,
            headers = headers,
            interceptor = cfInterceptor
        )
    }

    // 1. Ana Sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val document = getWithCf(url).document
        val homePages = mutableListOf<HomePageList>()

        val allItems = document.select("a.poster, article.poster, div.poster, div.movie-box, article, div.card, div.content-box a").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        if (allItems.isNotEmpty()) {
            homePages.add(HomePageList("Son Eklenenler / Öne Çıkanlar", allItems))
        }

        return newHomePageResponse(homePages)
    }

    // 2. Arama
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = getWithCf(searchUrl).document

        return document.select("a.poster, article.poster, div.poster, div.movie-box, article, div.card").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        var href = if (this.tagName() == "a") {
            this.attr("href")
        } else {
            this.selectFirst("a")?.attr("href")
        } ?: return null

        if (href.isBlank() || href == "#" || href.contains("/kategori/") || href.contains("/imdb/")) return null
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
        val document = getWithCf(url).document
        val title = document.selectFirst("h1, header h1")?.text()?.trim() ?: "Film"
        val imgEl = document.selectFirst("div.poster img, article img")
        
        var poster: String? = null
        if (imgEl != null) {
            val dataSrc = imgEl.attr("data-src").trim()
            val src = imgEl.attr("src").trim()
            poster = if (dataSrc.isNotEmpty()) dataSrc else src
        }

        val plot = document.selectFirst("div.post-content, p.story, div.overview")?.text()?.trim()
        val isTvSeries = url.contains("/dizi/")

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.episode-list a, ul.episodes a, div.seasons-list a").forEach { ep ->
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

    // 4. Video Oynatıcı Bağlantılarını Çözme
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getWithCf(data).document
        var found = false

        val iframes = document.select("iframe[src], iframe[data-src]")
        for (iframe in iframes) {
            val srcAttr = iframe.attr("src").trim()
            val dataSrcAttr = iframe.attr("data-src").trim()
            var src = if (srcAttr.isNotEmpty()) srcAttr else dataSrcAttr

            if (src.startsWith("//")) src = "https:$src"

            if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("google")) {
                if (loadExtractor(src, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        return found
    }
}
