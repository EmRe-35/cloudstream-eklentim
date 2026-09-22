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

    // 1. Ana Sayfa (Birden Fazla Sekme/Kategori)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Son Eklenenler
        val recentItems = document.select("a.poster, div.poster, article.poster, div.card, div.movie-box, article").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        if (recentItems.isNotEmpty()) {
            homePages.add(HomePageList("Son Eklenenler", recentItems))
        }

        // Popüler Filmler (Sitede Varsa)
        val popularItems = document.select("div.popular-slider a, div.top-movies a, div.sidebar-poster").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        if (popularItems.isNotEmpty()) {
            homePages.add(HomePageList("Popüler", popularItems))
        }

        return newHomePageResponse(homePages)
    }

    // 2. Arama
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query"
        val document = app.get(searchUrl).document

        return document.select("a.poster, div.poster, article.poster, div.card, div.movie-box, article").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        var href = if (this.tagName() == "a") {
            this.attr("href")
        } else {
            this.selectFirst("a")?.attr("href")
        } ?: return null

        if (href.isBlank() || href == "#") return null
        if (!href.startsWith("http")) {
            href = "$mainUrl$href"
        }

        val titleEl = this.selectFirst("h2, h3, .title, a.title, strong, img")
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
        val document = app.get(url).document
        val title = document.selectFirst("h1, header h1, div.title h1")?.text()?.trim() ?: "Bilinmeyen Başlık"
        
        val imgEl = document.selectFirst("div.poster img, .poster-container img, img.cover, article img")
        var poster: String? = null
        if (imgEl != null) {
            val dataSrc = imgEl.attr("data-src").trim()
            val src = imgEl.attr("src").trim()
            poster = if (dataSrc.isNotEmpty()) dataSrc else src
        }

        val plot = document.selectFirst("div.post-content, div.description, p.story, div.overview, p")?.text()?.trim()
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

    // 4. Video Oynatıcı Bağlantılarını Çekme (Düzeltildi)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // 1. Tüm Player Tabları ve iframe Linkleri
        val iframeUrls = mutableListOf<String>()

        document.select("iframe, div.video-container iframe, div.player-container iframe").forEach { iframe ->
            val src = iframe.attr("src").trim().ifEmpty { iframe.attr("data-src").trim() }
            if (src.isNotEmpty()) iframeUrls.add(src)
        }

        // Alternatif Player Butonları / Tabları
        document.select("nav.player-tabs button, nav.player-tabs a, div.alternative-links a, [data-video], [data-url]").forEach { element ->
            val videoUrl = element.attr("data-video").trim().ifEmpty { element.attr("data-url").trim().ifEmpty { element.attr("href").trim() } }
            if (videoUrl.isNotEmpty() && videoUrl != "#" && !videoUrl.startsWith("javascript")) {
                iframeUrls.add(videoUrl)
            }
        }

        for (rawUrl in iframeUrls.distinct()) {
            var url = rawUrl
            if (url.startsWith("//")) {
                url = "https:$url"
            } else if (!url.startsWith("http")) {
                url = "$mainUrl$url"
            }

            // Extractor'lara Gönder (Vidmoly, Rapidrame, Doodstream, Hqq vb.)
            val loaded = loadExtractor(url, data, subtitleCallback, callback)
            if (loaded) {
                found = true
            } else {
                // Doğrudan .mp4 veya .m3u8 linkiyse
                if (url.contains(".mp4") || url.contains(".m3u8")) {
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = url,
                            referer = "$mainUrl/",
                            quality = Qualities.Unknown.value,
                            type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                    found = true
                }
            }
        }

        return found
    }
}
