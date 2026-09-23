package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLEncoder

class HdFilmCehennemiProvider : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override var lang = "tr"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/128.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    /*
     * Ana sayfa kategorileri
     */
    override val mainPage = mainPageOf(
        "$mainUrl/category/film-izle-2/" to "Son Eklenenler",
        "$mainUrl/category/nette-ilk-filmler-1/" to "Nette İlk",
        "$mainUrl/category/tavsiye-filmler-izle3/" to "Tavsiye Filmler",
        "$mainUrl/tur/aksiyon-filmleri-izleyin-8/" to "Aksiyon",
        "$mainUrl/tur/komedi-filmlerini-izleyin-2/" to "Komedi",
        "$mainUrl/category/1080p-hd-film-izle-5/" to "1080p"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val currentPage: Int = page.coerceAtLeast(1)

        val baseUrl = normalizeUrl(request.data)

        val pageUrl: String =
            if (currentPage <= 1) {
                baseUrl
            } else {
                if (baseUrl.endsWith("/")) {
                    "$baseUrl?page=$currentPage"
                } else {
                    "$baseUrl/?page=$currentPage"
                }
            }

        return try {

            val document: Document = app.get(
                pageUrl,
                headers = headers
            ).document

            val results: List<SearchResponse> =
                parseResults(document)

            newHomePageResponse(
                listOf(
                    HomePageList(
                        request.name,
                        results,
                        isHorizontalImages = true
                    )
                ),
                hasNext = results.isNotEmpty()
            )

        } catch (error: Exception) {

            logError(
                "Ana sayfa yüklenemedi: $pageUrl",
                error
            )

            newHomePageResponse(
                emptyList(),
                hasNext = false
            )
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery: String = URLEncoder
            .encode(query.trim(), "UTF-8")
            .replace("+", "%20")

        val searchUrls: List<String> = listOf(
            "$mainUrl/search/$encodedQuery/",
            "$mainUrl/?s=$encodedQuery"
        )

        for (searchUrl: String in searchUrls) {

            try {

                val document: Document = app.get(
                    searchUrl,
                    headers = headers
                ).document

                val results: List<SearchResponse> =
                    parseResults(document)

                if (results.isNotEmpty()) {
                    return results
                }

            } catch (error: Exception) {

                logError(
                    "Arama başarısız: $searchUrl",
                    error
                )
            }
        }

        return emptyList()
    }

    /*
     * Film listesini parse eder.
     *
     * HDFilmCehennemi'ndeki gerçek kartlar:
     *
     * <a class="poster poster-slider"
     *    href="..."
     *    title="...">
     *
     *     <img data-src="...">
     *
     * </a>
     */
    private fun parseResults(
        document: Document
    ): List<SearchResponse> {

        val results: MutableList<SearchResponse> =
            mutableListOf()

        /*
         * Öncelikli gerçek film kartı selector'ü.
         */
        val posterElements: Elements =
            document.select("a.poster[href]")

        for (element: Element in posterElements) {

            val result: SearchResponse? =
                element.toSearchResult()

            if (result != null) {
                results.add(result)
            }
        }

        /*
         * Bazı arama/kategori sayfalarında farklı kart
         * yapısı olabilir. Eğer poster selector'ü hiçbir
         * sonuç vermezse eski alternatif selector'leri dene.
         */
        if (results.isEmpty()) {

            val fallbackSelector: String =
                "article," +
                    ".movie-box," +
                    ".movie-item," +
                    ".film-box," +
                    ".film-item," +
                    ".card"

            val fallbackElements: Elements =
                document.select(fallbackSelector)

            for (element: Element in fallbackElements) {

                val result: SearchResponse? =
                    element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }
        }

        return results.distinctBy { result: SearchResponse ->
            result.url
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val linkElement: Element? =
            if (tagName() == "a" && hasAttr("href")) {
                this
            } else {
                findElement(
                    this,
                    "a.poster[href],a[href]"
                )
            }

        if (linkElement == null) {
            return null
        }

        val contentUrl: String =
            normalizeUrl(
                linkElement.attr("href")
            )

        if (!isValidContentUrl(contentUrl)) {
            return null
        }

        val imageElement: Element? =
            findElement(
                this,
                "img"
            )

        val title: String? =
            firstNonBlank(
                linkElement.attr("title"),
                findElement(this, "h1")?.text(),
                findElement(this, "h2")?.text(),
                findElement(this, "h3")?.text(),
                findElement(this, ".title")?.text(),
                findElement(this, ".film-title")?.text(),
                findElement(this, ".movie-title")?.text(),
                imageElement?.attr("alt")
            )?.trim()

        if (title.isNullOrBlank()) {
            return null
        }

        val posterUrl: String? =
            imageElement?.let { image: Element ->

                val rawPoster: String? =
                    firstNonBlank(
                        image.attr("data-src"),
                        image.attr("data-lazy-src"),
                        image.attr("data-original"),
                        image.attr("src")
                    )

                if (rawPoster.isNullOrBlank()) {
                    null
                } else {
                    normalizeUrl(rawPoster)
                }
            }

        val type: TvType =
            if (isSeriesUrl(contentUrl)) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

        return newMovieSearchResponse(
            title,
            contentUrl,
            type
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val pageUrl: String =
            normalizeUrl(url)

        return try {

            val document: Document =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            val title: String =
                firstNonBlank(
                    findElement(document, "h1")?.text(),
                    findElement(document, "h2")?.text(),
                    findElement(
                        document,
                        "meta[property=og:title]"
                    )?.attr("content"),
                    document.title()
                )?.trim()
                    ?: "Bilinmeyen içerik"

            val posterElement: Element? =
                findElement(
                    document,
                    "meta[property=og:image]," +
                        "div.poster img," +
                        ".poster img," +
                        ".movie-poster img," +
                        "article img"
                )

            val poster: String? =
                if (posterElement == null) {

                    null

                } else {

                    val rawPoster: String? =
                        firstNonBlank(
                            posterElement.attr("content"),
                            posterElement.attr("data-src"),
                            posterElement.attr("data-lazy-src"),
                            posterElement.attr("data-original"),
                            posterElement.attr("src")
                        )

                    if (rawPoster.isNullOrBlank()) {
                        null
                    } else {
                        normalizeUrl(rawPoster)
                    }
                }

            val plot: String? =
                firstNonBlank(
                    findElement(
                        document,
                        "meta[name=description]"
                    )?.attr("content"),

                    findElement(
                        document,
                        "meta[property=og:description]"
                    )?.attr("content"),

                    findElement(
                        document,
                        ".description"
                    )?.text(),

                    findElement(
                        document,
                        ".overview"
                    )?.text(),

                    findElement(
                        document,
                        ".story"
                    )?.text(),

                    findElement(
                        document,
                        ".post-content"
                    )?.text()
                )?.trim()

            if (isSeriesUrl(pageUrl)) {

                val episodes: List<Episode> =
                    parseEpisodes(document)

                newTvSeriesLoadResponse(
                    title,
                    pageUrl,
                    TvType.TvSeries,
                    episodes
                ) {

                    this.posterUrl = poster
                    this.plot = plot
                }

            } else {

                newMovieLoadResponse(
                    title,
                    pageUrl,
                    TvType.Movie,
                    pageUrl
                ) {

                    this.posterUrl = poster
                    this.plot = plot
                }
            }

        } catch (error: Exception) {

            logError(
                "Detay sayfası yüklenemedi: $pageUrl",
                error
            )

            newMovieLoadResponse(
                "İçerik yüklenemedi",
                pageUrl,
                TvType.Movie,
                pageUrl
            )
        }
    }

    private fun parseEpisodes(
        document: Document
    ): List<Episode> {

        val selector: String =
            ".episode-list a[href]," +
                ".episodes a[href]," +
                "ul.episodes a[href]," +
                ".season-list a[href]," +
                ".seasons-list a[href]," +
                "a[href*='/bolum']," +
                "a[href*='-bolum-']"

        val elements: Elements =
            document.select(selector)

        val episodes: MutableList<Episode> =
            mutableListOf()

        for (element: Element in elements) {

            val episodeUrl: String =
                normalizeUrl(
                    element.attr("href")
                )

            if (!isValidContentUrl(episodeUrl)) {
                continue
            }

            val episodeName: String? =
                firstNonBlank(
                    element.text(),
                    element.attr("title"),
                    element.attr("aria-label")
                )?.trim()

            if (episodeName.isNullOrBlank()) {
                continue
            }

            episodes.add(
                newEpisode(episodeUrl) {
                    name = episodeName
                }
            )
        }

        return episodes.distinctBy {
            episode: Episode ->
            episode.data
        }
    }

    /*
     * Video kaynaklarını bulur.
     *
     * ÖNEMLİ:
     *
     * HDFilmCehennemi sayfasındaki:
     *
     * data-video="322665"
     *
     * doğrudan video URL'si değildir.
     *
     * Bu nedenle artık data-video değerini URL gibi
     * extractor'a göndermiyoruz.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageUrl: String =
            normalizeUrl(data)

        val candidateUrls: MutableSet<String> =
            linkedSetOf()

        var found: Boolean = false

        try {

            val document: Document =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            /*
             * 1. IFRAME kaynakları
             */
            val iframeElements: Elements =
                document.select(
                    "iframe[src]," +
                        "iframe[data-src]," +
                        "iframe[data-url]"
                )

            for (iframe: Element in iframeElements) {

                addCandidate(
                    candidateUrls,
                    iframe.attr("src")
                )

                addCandidate(
                    candidateUrls,
                    iframe.attr("data-src")
                )

                addCandidate(
                    candidateUrls,
                    iframe.attr("data-url")
                )
            }

            /*
             * 2. Doğrudan video kaynakları
             */
            val sourceElements: Elements =
                document.select(
                    "video source[src]," +
                        "video[src]," +
                        "source[src]"
                )

            for (source: Element in sourceElements) {

                addCandidate(
                    candidateUrls,
                    source.attr("src")
                )
            }

            /*
             * 3. HDFilmCehennemi alternatif player'ları
             *
             * Burada sadece gerçek URL içeren attribute'ları
             * kontrol ediyoruz.
             *
             * data-video="322665" gibi numeric ID'leri
             * candidate olarak eklemiyoruz.
             */
            val alternativeElements: Elements =
                document.select(
                    "button.alternative-link"
                )

            for (element: Element in alternativeElements) {

                val dataUrl: String? =
                    firstNonBlank(
                        element.attr("data-url"),
                        element.attr("data-src"),
                        element.attr("data-player"),
                        element.attr("data-stream"),
                        element.attr("data-file")
                    )

                addCandidate(
                    candidateUrls,
                    dataUrl
                )
            }

            /*
             * 4. Sayfada doğrudan bulunan M3U8/MP4 adresleri
             */
            val mediaElements: Elements =
                document.select(
                    "[src*='.m3u8']," +
                        "[data-src*='.m3u8']," +
                        "[src*='.mp4']," +
                        "[data-src*='.mp4']"
                )

            for (element: Element in mediaElements) {

                addCandidate(
                    candidateUrls,
                    element.attr("src")
                )

                addCandidate(
                    candidateUrls,
                    element.attr("data-src")
                )
            }

            /*
             * Bulunan her gerçek URL'yi CloudStream extractor
             * sistemine gönder.
             */
            for (candidate: String in candidateUrls) {

                try {

                    /*
                     * Eğer kaynak doğrudan M3U8 ise extractor'a
                     * gerek kalmadan CloudStream'e ver.
                     */
                    if (
                        candidate.contains(
                            ".m3u8",
                            ignoreCase = true
                        )
                    ) {

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "HDFilmCehennemi",
                                url = candidate,
                                type = ExtractorLinkType.M3U8
                            ) {

                                referer = pageUrl

                                quality =
                                    Qualities.Unknown.value

                                headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to pageUrl
                                )
                            }
                        )

                        found = true

                        continue
                    }

                    /*
                     * MP4 doğrudan kaynak ise.
                     */
                    if (
                        candidate.contains(
                            ".mp4",
                            ignoreCase = true
                        )
                    ) {

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "HDFilmCehennemi",
                                url = candidate
                            ) {

                                referer = pageUrl

                                quality =
                                    Qualities.Unknown.value

                                headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to pageUrl
                                )
                            }
                        )

                        found = true

                        continue
                    }

                    /*
                     * Rapidrame / Vidmoly vb. harici player.
                     */
                    val extractorFound: Boolean =
                        loadExtractor(
                            candidate,
                            pageUrl,
                            subtitleCallback,
                            callback
                        )

                    if (extractorFound) {
                        found = true
                    }

                } catch (error: Exception) {

                    logError(
                        "Extractor başarısız: $candidate",
                        error
                    )
                }
            }

        } catch (error: Exception) {

            logError(
                "Video kaynakları yüklenemedi: $pageUrl",
                error
            )
        }

        return found
    }

    /*
     * URL'yi güvenli şekilde candidate listesine ekler.
     */
    private fun addCandidate(
        candidates: MutableSet<String>,
        rawUrl: String?
    ) {

        if (rawUrl.isNullOrBlank()) {
            return
        }

        val url: String =
            normalizeUrl(rawUrl)

        if (
            url.startsWith("http://") ||
            url.startsWith("https://")
        ) {
            candidates.add(url)
        }
    }

    private fun findElement(
        document: Document,
        selector: String
    ): Element? {

        val elements: Elements =
            document.select(selector)

        return elements.firstOrNull()
    }

    private fun findElement(
        element: Element,
        selector: String
    ): Element? {

        val elements: Elements =
            element.select(selector)

        return elements.firstOrNull()
    }

    /*
     * URL normalizasyonu.
     */
    private fun normalizeUrl(
        rawUrl: String?
    ): String {

        if (rawUrl.isNullOrBlank()) {
            return ""
        }

        val value: String =
            rawUrl.trim()

        return when {

            value.startsWith("//") ->
                "https:$value"

            value.startsWith("http://") ||
                value.startsWith("https://") ->
                value

            value.startsWith("/") ->
                "$mainUrl$value"

            else ->
                "$mainUrl/$value"
        }
    }

    private fun isSeriesUrl(
        url: String
    ): Boolean {

        return url.contains("/dizi/") ||
            url.contains("/series/") ||
            url.contains("/tv/")
    }

    /*
     * Sadece HDFilmCehennemi içindeki gerçek içerik
     * sayfalarını kabul eder.
     */
    private fun isValidContentUrl(
        url: String
    ): Boolean {

        if (url.isBlank()) {
            return false
        }

        if (
            url == mainUrl ||
            url == "$mainUrl/" ||
            url.contains("/kategori/") ||
            url.contains("/category/") ||
            url.contains("/imdb/") ||
            url.contains("/sayfa/") ||
            url.contains("/page/")
        ) {
            return false
        }

        return url.startsWith("$mainUrl/")
    }

    private fun firstNonBlank(
        vararg values: String?
    ): String? {

        return values.firstOrNull {
            !it.isNullOrBlank()
        }
    }

    private fun logError(
        message: String,
        error: Throwable
    ) {

        println(
            "HDFilmCehennemi: $message | " +
                "${error::class.simpleName}: ${error.message}"
        )
    }
}
