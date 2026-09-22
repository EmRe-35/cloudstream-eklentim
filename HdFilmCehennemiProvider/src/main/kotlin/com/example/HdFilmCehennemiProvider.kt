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

    // 4. Yeni Yöntem: Bütün Alternatif Player Endpoint'lerini Çözme
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = headers)
        val document = response.document
        var found = false

        val urlsToTest = mutableListOf<String>()

        // Yöntem A: Sitedeki tab/player butonlarındaki 'data-id', 'data-embed' veya 'data-url' özniteliklerini topla
        document.select("[data-id], [data-embed], [data-url], [data-video], button, a.nav-link").forEach { el ->
            val dataUrl = el.attr("data-url").ifEmpty { el.attr("data-embed") }.ifEmpty { el.attr("data-video") }
            if (dataUrl.isNotEmpty()) {
                urlsToTest.add(dataUrl)
            }
        }

        // Yöntem B: Tüm iframe'lerin varsayılan ve lazy-load linklerini al
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotEmpty()) urlsToTest.add(src)
        }

        // Bulunan URL'leri işle
        for (rawUrl in urlsToTest.distinct()) {
            var targetUrl = rawUrl.trim()
            if (targetUrl.startsWith("//")) targetUrl = "https:$targetUrl"
            if (!targetUrl.startsWith("http")) targetUrl = "$mainUrl$targetUrl"

            // Filtreleme (gereksiz sosyal medya/reklam linklerini atla)
            if (targetUrl.contains("facebook") || targetUrl.contains("google") || targetUrl.contains("disqus")) continue

            // Eğer yönlendirilen sayfa doğrudan bir video oynatıcı ise Extractor'a gönder
            if (loadExtractor(targetUrl, data, subtitleCallback, callback)) {
                found = true
            } else {
                // Eğer doğrudan çözülemediyse, bu embed sayfasının içine girip içindeki asıl iframe'i ara
                try {
                    val subDoc = app.get(targetUrl, headers = mapOf("Referer" to data, "User-Agent" to userAgent)).document
                    val innerIframe = subDoc.selectFirst("iframe")?.attr("src")
                    if (!innerIframe.isNullOrEmpty()) {
                        var finalUrl = innerIframe
                        if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
                        if (loadExtractor(finalUrl, targetUrl, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        return found
    }
}
