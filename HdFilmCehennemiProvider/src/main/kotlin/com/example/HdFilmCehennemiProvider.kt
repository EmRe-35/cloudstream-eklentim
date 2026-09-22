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
        val homePages = mutableListOf<HomePageList>()

        val allItems = document.select("a.poster, article.poster, div.poster, div.movie-box, article, div.card, div.content-box a").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        if (allItems.isNotEmpty()) {
            homePages.add(HomePageList("Son Eklenenler / Öne Çıkanlar", allItems))
        }

        val categoryItems = document.select("div.col-md-2 a, div.col-6 a, div.sidebar a").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        if (categoryItems.isNotEmpty()) {
            homePages.add(HomePageList("Popüler İçerikler", categoryItems))
        }

        return newHomePageResponse(homePages)
    }

    // 2. Arama
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl, headers = headers).document

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

        if (href.isBlank() || href == "#" || href.contains("/kategori/") || href.contains("/imdb/") || href.contains("/tur/")) return null
        if (!href.startsWith("http")) {
            href = "$mainUrl$href"
        }

        val titleEl = this.selectFirst("h2, h3, .title, a.title, strong, img, span.name")
        val rawTitle = if (titleEl != null && titleEl.tagName() == "img") {
            titleEl.attr("alt")
        } else {
            titleEl?.text() ?: this.attr("title")
        }
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
        val title = document.selectFirst("h1, header h1, div.title h1")?.text()?.trim() ?: "Bilinmeyen Başlık"
        
        val imgEl = document.selectFirst("div.poster img, .poster-container img, img.cover, article img, div.movie-poster img")
        var poster: String? = null
        if (imgEl != null) {
            val dataSrc = imgEl.attr("data-src").trim()
            val src = imgEl.attr("src").trim()
            poster = if (dataSrc.isNotEmpty()) dataSrc else src
        }

        val plot = document.selectFirst("div.post-content, div.description, p.story, div.overview, p, div.article-content")?.text()?.trim()
        val isTvSeries = url.contains("/dizi/")

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            document.select("div.episode-list a, ul.episodes a, div.seasons-list a, a.episode").forEach { ep ->
                var epHref = ep.attr("href").trim()
                if (epHref.isNotEmpty()) {
                    if (!epHref.startsWith("http")) epHref = "$mainUrl$epHref"
                    val rawEpName = ep.text().trim()
                    val epName = if (rawEpName.isNotEmpty()) rawEpName else "Bölüm"
                    
                    val newEp = newEpisode(epHref) {
                        this.name = epName
                    }
                    episodes.add(newEp)
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

    // 4. Gelişmiş Video Bağlantı Ayıklama ve Taraması
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        var found = false
        val urlsToTest = mutableSetOf<String>()

        // A. Doğrudan sayfadaki tüm embed/iframe/player URL'lerini yakala
        document.select("iframe, iframe[data-src], iframe[src], [data-video], [data-url], nav.player-tabs a, button[data-post]").forEach { el ->
            val src = el.attr("src").ifEmpty { el.attr("data-src") }
                .ifEmpty { el.attr("data-video") }
                .ifEmpty { el.attr("data-url") }
                .ifEmpty { el.attr("href") }.trim()

            if (src.isNotEmpty() && src != "#" && !src.startsWith("javascript")) {
                urlsToTest.add(src)
            }
        }

        // B. Her bir yakalanan bağlantıyı doğrula ve extractor'a ver
        for (rawUrl in urlsToTest) {
            var url = rawUrl
            if (url.startsWith("//")) {
                url = "https:$url"
            } else if (!url.startsWith("http")) {
                url = "$mainUrl$url"
            }

            // Cloudstream'in genel extractor yapısını çalıştır
            val isExtracted = loadExtractor(url, data, subtitleCallback, callback)
            if (isExtracted) {
                found = true
            } else {
                // Eğer doğrudan çözülemediyse (Örn: HDFilmCehennemi'nin kendi özel player'ı ise)
                runCatching {
                    val pageRes = app.get(url, headers = mapOf("Referer" to data, "User-Agent" to userAgent))
                    val innerDoc = pageRes.document
                    
                    // Alt iframe'leri kontrol et (Vidmoly, Rapidrame vb.)
                    innerDoc.select("iframe").forEach { iframe ->
                        var iframeSrc = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.trim()
                        if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"
                        if (iframeSrc.isNotEmpty()) {
                            if (loadExtractor(iframeSrc, url, subtitleCallback, callback)) {
                                found = true
                            }
                        }
                    }

                    // Doğrudan .m3u8 akışı içeriyorsa ekle
                    val scriptContent = innerDoc.select("script").html()
                    val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                    m3u8Regex.findAll(scriptContent).forEach { match ->
                        val streamUrl = match.value
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name - HLS Stream",
                                url = streamUrl,
                                referer = url,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8,
                                headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to url,
                                    "Origin" to mainUrl
                                )
                            )
                        )
                        found = true
                    }
                }
            }
        }

        return found
    }
}
