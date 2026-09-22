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

    // 1. Ana Sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        val document = app.get(url).document

        // Sitedeki film/dizi kartlarını yakalayan esnek seçiciler
        val items = document.select("a.poster, div.poster, article.poster, div.card, div.movie-box, article").mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse("Son Eklenenler", items)
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
        // Link bulma
        var href = if (this.tagName() == "a") {
            this.attr("href")
        } else {
            this.selectFirst("a")?.attr("href")
        } ?: return null

        if (href.isBlank() || href == "#") return null
        if (!href.startsWith("http")) {
            href = "$mainUrl$href"
        }

        // Başlık bulma
        val title = this.selectFirst("h2, h3, .title, a.title, strong, img")?.let {
            if (it.tagName() == "img") it.attr("alt") else it.text()
        }?.ifBlank { null } ?: this.attr("title").ifBlank { null } ?: return null

        // Afiş bulma
        var posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { null }
                ?: img.attr("data-lazy-src").ifBlank { null }
                ?: img.attr("src").ifBlank { null }
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
        val title = document.selectFirst("h1, header h1, div.title h1")?.text() ?: "Bilinmeyen Başlık"
        val poster = document.selectFirst("div.poster img, .poster-container img, img.cover, article img")?.let { img ->
            img.attr("data-src").ifBlank { null } ?: img.attr("src")
        }
        val plot = document.selectFirst("div.post-content, div.description, p.story, div.overview, p")?.text()

        val isTvSeries = url.contains("/dizi/")

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            document.select("div.episode-list a, ul.episodes a, div.seasons-list a, a.episode").forEach { ep ->
                var epHref = ep.attr("href")
                if (epHref.isNotBlank()) {
                    if (!epHref.startsWith("http")) epHref = "$mainUrl$epHref"
                    val epName = ep.text().ifBlank { "Bölüm" }
                    
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

    // 4. Video Oynatıcı Bağlantıları
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        val iframes = document.select("iframe, div.video-container iframe, div.player-container iframe")

        for (iframe in iframes) {
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }

            if (src.startsWith("//")) {
                src = "https:$src"
            }

            if (src.isNotBlank()) {
                val loaded = loadExtractor(src, data, subtitleCallback, callback)
                if (loaded) {
                    found = true
                } else {
                    if (src.contains(".mp4") || src.contains(".m3u8")) {
                        callback(
                            ExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = src,
                                referer = "$mainUrl/",
                                quality = Qualities.Unknown.value,
                                type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
