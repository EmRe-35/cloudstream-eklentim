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

    // 4. Video Oynatıcı Bağlantılarını Çekme (AJAX & Embed Uyarlaması)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false
        val extractedUrls = mutableListOf<String>()

        // A. Doğrudan HTML içindeki tüm iframe'leri tara
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").trim().ifEmpty { iframe.attr("data-src").trim() }
            if (src.isNotEmpty()) extractedUrls.add(src)
        }

        // B. Sitedeki Player Tab / Alternatif Kaynak Butonlarını Tara
        document.select("[data-video], [data-url], [data-post], [data-id], .player-tab, nav.player-tabs a").forEach { el ->
            val videoAttr = el.attr("data-video").trim()
                .ifEmpty { el.attr("data-url").trim() }
                .ifEmpty { el.attr("href").trim() }

            if (videoAttr.isNotEmpty() && videoAttr != "#" && !videoAttr.startsWith("javascript")) {
                extractedUrls.add(videoAttr)
            }
        }

        // C. Elde edilen tüm bağlantıları çözümleyicilere (Extractor) gönder
        for (rawUrl in extractedUrls.distinct()) {
            var url = rawUrl
            if (url.startsWith("//")) {
                url = "https:$url"
            } else if (!url.startsWith("http")) {
                url = "$mainUrl$url"
            }

            // Cloudstream'in yerleşik extractor'larını çalıştır (Vidmoly, Rapidrame vb.)
            val loaded = loadExtractor(url, data, subtitleCallback, callback)
            if (loaded) {
                found = true
            } else {
                // Eğer dönen bağlantı bir embed/player sayfası ise o sayfayı da indirip içindeki iframe'i tara
                if (url.contains("hdfilmcehennemi") || url.contains("player") || url.contains("embed")) {
                    runCatching {
                        val embedDoc = app.get(url, referer = "$mainUrl/").document
                        embedDoc.select("iframe").forEach { innerIframe ->
                            var innerSrc = innerIframe.attr("src").trim().ifEmpty { innerIframe.attr("data-src").trim() }
                            if (innerSrc.startsWith("//")) innerSrc = "https:$innerSrc"
                            if (innerSrc.isNotEmpty()) {
                                if (loadExtractor(innerSrc, url, subtitleCallback, callback)) {
                                    found = true
                                }
                            }
                        }
                    }
                }
            }
        }

        return found
    }
}
