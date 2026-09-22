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

    // Gerçek bir Windows 11 Chrome 128 Tarayıcı Kimliği
    private val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    // Sıradan bir masaüstü tarayıcının attığı BİREBİR HTTP Başlıkları
    private val browserHeaders = mapOf(
        "User-Agent" to browserUserAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"128\", \"Not=A?Brand\";v=\"24\", \"Google Chrome\";v=\"128\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    // 1. Ana Sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val document = app.get(url, headers = browserHeaders).document
        val homePages = mutableListOf<HomePageList>()

        val allItems = document.select("a.poster, article.poster, div.poster, div.movie-box, article, div.card").mapNotNull { element ->
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
        val document = app.get(searchUrl, headers = browserHeaders).document

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

        val titleEl = this.selectFirst("h2, h3, .title, a.title, strong, img")
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
        val document = app.get(url, headers = browserHeaders).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Film"
        val imgEl = document.selectFirst("div.poster img, article img")
        val poster = imgEl?.attr("data-src")?.ifEmpty { imgEl.attr("src") }
        val plot = document.selectFirst("div.post-content, p.story")?.text()?.trim()
        val isTvSeries = url.contains("/dizi/")

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.episode-list a, ul.episodes a").forEach { ep ->
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

    // 4. Tarayıcı Taklidi ile Link Çekme
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Filmin kendi sayfasına sıradan bir tarayıcı gibi istek atıyoruz
        val pageHeaders = browserHeaders.toMutableMap().apply {
            put("Referer", "$mainUrl/")
        }
        val document = app.get(data, headers = pageHeaders).document
        var found = false

        // Iframe'leri tara
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            var src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.trim()
            if (src.startsWith("//")) src = "https:$src"

            if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("google")) {
                // Video sunucusuna giderken de tam tarayıcı başlıklarını iletiyoruz
                val loaded = loadExtractor(src, data, subtitleCallback) { link ->
                    // Oynatıcıya giden ExtractorLink içine de tarayıcı başlıklarını gömüyoruz
                    val newLink = ExtractorLink(
                        source = link.source,
                        name = link.name,
                        url = link.url,
                        referer = data,
                        quality = link.quality,
                        type = link.type,
                        headers = browserHeaders + mapOf("Referer" to data, "Origin" to mainUrl)
                    )
                    callback.invoke(newLink)
                }
                if (loaded) found = true
            }
        }

        return found
    }
}
