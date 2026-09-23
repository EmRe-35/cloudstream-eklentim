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
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

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

        val currentPage: Int =
            page.coerceAtLeast(1)

        val baseUrl =
            normalizeUrl(request.data)

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

            val document: Document =
                app.get(
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

        val encodedQuery: String =
            URLEncoder
                .encode(
                    query.trim(),
                    "UTF-8"
                )
                .replace(
                    "+",
                    "%20"
                )

        val searchUrls: List<String> =
            listOf(
                "$mainUrl/search/$encodedQuery/",
                "$mainUrl/?s=$encodedQuery"
            )

        for (searchUrl: String in searchUrls) {

            try {

                val document: Document =
                    app.get(
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

    private fun parseResults(
        document: Document
    ): List<SearchResponse> {

        val results: MutableList<SearchResponse> =
            mutableListOf()

        val posterElements: Elements =
            document.select(
                "a.poster[href]"
            )

        for (element: Element in posterElements) {

            val result: SearchResponse? =
                element.toSearchResult()

            if (result != null) {
                results.add(result)
            }
        }

        if (results.isEmpty()) {

            val fallbackSelector: String =
                "article," +
                    ".movie-box," +
                    ".movie-item," +
                    ".film-box," +
                    ".film-item," +
                    ".card"

            val fallbackElements: Elements =
                document.select(
                    fallbackSelector
                )

            for (element: Element in fallbackElements) {

                val result: SearchResponse? =
                    element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }
        }

        return results.distinctBy {
            result: SearchResponse ->
            result.url
        }
    }

    private fun Element.toSearchResult():
        SearchResponse? {

        val linkElement: Element? =
            if (
                tagName() == "a" &&
                hasAttr("href")
            ) {
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
                findElement(
                    this,
                    "h1"
                )?.text(),
                findElement(
                    this,
                    "h2"
                )?.text(),
                findElement(
                    this,
                    "h3"
                )?.text(),
                findElement(
                    this,
                    ".title"
                )?.text(),
                findElement(
                    this,
                    ".film-title"
                )?.text(),
                findElement(
                    this,
                    ".movie-title"
                )?.text(),
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
                    findElement(
                        document,
                        "h1"
                    )?.text(),
                    findElement(
                        document,
                        "h2"
                    )?.text(),
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

        val episodes:
            MutableList<Episode> =
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
     * ============================================================
     * DİNAMİK RAPIDRAME
     * ============================================================
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val pageUrl =
                normalizeUrl(data)

            /*
             * Film sayfası
             */
            val pageDocument =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            /*
             * Sayfadaki iframe
             */
            val iframe =
                pageDocument
                    .select("iframe")
                    .firstOrNull()

            val iframeSrc =
                firstNonBlank(
                    iframe?.attr("src"),
                    iframe?.attr("data-src")
                )

            if (iframeSrc.isNullOrBlank()) {

                println(
                    "HDFilmCehennemi: iframe bulunamadı -> $pageUrl"
                )

                return false
            }

            val primaryIframe =
                normalizeUrl(
                    iframeSrc
                )

            /*
             * Alternatif kaynaklar
             */
            data class AlternativeSource(
                val name: String,
                val videoId: String?,
                val active: Boolean
            )

            val alternatives =
                pageDocument
                    .select(
                        ".alternative-link"
                    )
                    .map { element ->

                        AlternativeSource(
                            name =
                                element
                                    .text()
                                    .trim(),

                            videoId =
                                element
                                    .attr("data-video")
                                    .trim()
                                    .ifBlank {
                                        null
                                    },

                            active =
                                element
                                    .attr("data-active")
                                    .trim() == "1"
                        )
                    }

            /*
             * ====================================================
             * ROT13
             * ====================================================
             */
            fun rot13(
                value: String
            ): String {

                return value.map { c ->

                    when {

                        c in 'a'..'z' -> {
                            (
                                (
                                    c.code -
                                        'a'.code +
                                        13
                                    ) % 26 +
                                    'a'.code
                                ).toChar()
                        }

                        c in 'A'..'Z' -> {
                            (
                                (
                                    c.code -
                                        'A'.code +
                                        13
                                    ) % 26 +
                                    'A'.code
                                ).toChar()
                        }

                        else -> c
                    }

                }.joinToString("")
            }

            /*
             * ====================================================
             * Character Unmix
             * ====================================================
             */
            fun characterUnmix(
                value: String
            ): String {

                val output =
                    StringBuilder()

                for (
                    i in value.indices
                ) {

                    var charCode =
                        value[i].code

                    charCode =
                        (
                            charCode -
                                (
                                    399756995L %
                                        (i + 5)
                                    ) +
                                256
                            ) % 256

                    output.append(
                        charCode.toInt().toChar()
                    )
                }

                return output.toString()
            }

            /*
             * ====================================================
             * Base64
             * ====================================================
             */
            fun base64Decode(
                value: String
            ): String {

                val bytes =
                    android.util.Base64.decode(
                        value,
                        android.util.Base64.DEFAULT
                    )

                return bytes.toString(
                    Charsets.ISO_8859_1
                )
            }

            /*
             * ====================================================
             * Video URL kontrolü
             * ====================================================
             */
            fun isValidVideoUrl(
                url: String?
            ): Boolean {

                if (url.isNullOrBlank()) {
                    return false
                }

                return url.startsWith(
                    "https://"
                ) &&
                    (
                        url.contains(
                            ".m3u8"
                        ) ||
                            url.contains(
                                "/hls/"
                            ) ||
                            url.contains(
                                ".mp4"
                            )
                        )
            }

            /*
             * ====================================================
             * JS PACKER
             *
             * Kaynak scraper'daki unpackJS:
             *
             * k = k.split('|')
             * p.replace(/\b\w+\b/g, decode)
             * ====================================================
             */
            fun unpackJs(
                packedCode: String,
                base: Int,
                keywords: String
            ): String {

                val dictionary =
                    keywords.split("|")

                fun decodeWord(
                    word: String
                ): String {

                    var number =
                        0

                    for (
                        character in word
                    ) {

                        when {

                            character.isDigit() -> {

                                number =
                                    number * base +
                                        character
                                            .digitToInt()
                            }

                            character in 'a'..'z' -> {

                                number =
                                    number * base +
                                        character.code -
                                        'a'.code +
                                        10
                            }

                            character in 'A'..'Z' -> {

                                number =
                                    number * base +
                                        character.code -
                                        'A'.code +
                                        36
                            }
                        }
                    }

                    return if (
                        number >= 0 &&
                        number < dictionary.size &&
                        dictionary[number]
                            .isNotEmpty()
                    ) {

                        dictionary[number]

                    } else {

                        word
                    }
                }

                return Regex(
                    """\b\w+\b"""
                ).replace(
                    packedCode
                ) { match ->

                    decodeWord(
                        match.value
                    )
                }
            }

            /*
             * ====================================================
             * Variant 1
             *
             * reverse
             * -> ROT13
             * -> Base64
             * -> unmix
             * ====================================================
             */
            fun decodeVariant1(
                reversed: String
            ): String {

                var value =
                    rot13(
                        reversed
                    )

                value =
                    base64Decode(
                        value
                    )

                return characterUnmix(
                    value
                )
            }

            /*
             * ====================================================
             * Variant 2
             *
             * reverse
             * -> Base64
             * -> ROT13
             * -> unmix
             * ====================================================
             */
            fun decodeVariant2(
                reversed: String
            ): String {

                var value =
                    base64Decode(
                        reversed
                    )

                value =
                    rot13(
                        value
                    )

                return characterUnmix(
                    value
                )
            }

            /*
             * ====================================================
             * Variant 3
             *
             * Base64
             * -> reverse
             * -> ROT13
             * -> unmix
             *
             * Güncel yöntem
             * ====================================================
             */
            fun decodeVariant3(
                value: String
            ): String {

                var result =
                    base64Decode(
                        value
                    )

                result =
                    result
                        .reversed()

                result =
                    rot13(
                        result
                    )

                return characterUnmix(
                    result
                )
            }

            /*
             * ====================================================
             * Video URL decode
             * ====================================================
             */
            fun decodeVideoUrl(
                parts: List<String>
            ): String? {

                val value =
                    parts.joinToString("")

                val reversed =
                    value.reversed()

                /*
                 * Variant 3 önce
                 */
                try {

                    val result3 =
                        decodeVariant3(
                            value
                        )

                    if (
                        isValidVideoUrl(
                            result3
                        )
                    ) {
                        return result3
                    }

                } catch (
                    ignored: Exception
                ) {
                }

                /*
                 * Variant 1
                 */
                try {

                    val result1 =
                        decodeVariant1(
                            reversed
                        )

                    if (
                        isValidVideoUrl(
                            result1
                        )
                    ) {
                        return result1
                    }

                } catch (
                    ignored: Exception
                ) {
                }

                /*
                 * Variant 2
                 */
                try {

                    val result2 =
                        decodeVariant2(
                            reversed
                        )

                    if (
                        isValidVideoUrl(
                            result2
                        )
                    ) {
                        return result2
                    }

                } catch (
                    ignored: Exception
                ) {
                }

                return null
            }

            /*
             * ====================================================
             * IFRAME SCRAPER
             * ====================================================
             *
             * Güncel scraper'daki önemli nokta:
             *
             * iframe isteğinin Referer'ı BASE_URL oluyor.
             * ====================================================
             */
            suspend fun scrapeIframe(
                iframeUrl: String
            ): Pair<String, String>? {

                return try {

                    val iframeResponse =
                        app.get(
                            iframeUrl,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Accept" to
                                    "text/html,application/xhtml+xml," +
                                    "application/xml;q=0.9,*/*;q=0.8",
                                "Accept-Language" to
                                    "tr-TR,tr;q=0.9,en;q=0.8",

                                /*
                                 * Güncel scraper:
                                 *
                                 * httpGet(iframeSrc, BASE_URL)
                                 *
                                 * Yani iframe isteğinin referer'ı
                                 * hdfilmcehennemi.ws.
                                 */
                                "Referer" to
                                    "https://www.hdfilmcehennemi.ws/"
                            )
                        )

                    val html =
                        iframeResponse.text

                    if (
                        html.isBlank()
                    ) {
                        return null
                    }

                    /*
                     * =================================================
                     * Packed JS
                     * =================================================
                     */
                    val packedRegex =
                        Regex(
                            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+)',(\d+),(\d+),'([^']+)'""",
                            setOf(
                                RegexOption.DOT_MATCHES_ALL
                            )
                        )

                    val packedMatch =
                        packedRegex.find(
                            html
                        )

                    if (
                        packedMatch != null
                    ) {

                        val packedCode =
                            packedMatch
                                .groupValues[1]

                        val base =
                            packedMatch
                                .groupValues[2]
                                .toInt()

                        val count =
                            packedMatch
                                .groupValues[3]
                                .toInt()

                        val keywords =
                            packedMatch
                                .groupValues[4]

                        /*
                         * count JS packer'ın parçası.
                         * unpackJS mantığı dictionary üzerinden
                         * çalıştığı için burada ayrıca kullanılmıyor.
                         */
                        @Suppress(
                            "UNUSED_VARIABLE"
                        )
                        val unusedCount =
                            count

                        val decodedJs =
                            unpackJs(
                                packedCode,
                                base,
                                keywords
                            )

                        /*
                         * dc_xxx([...])
                         */
                        val partsMatch =
                            Regex(
                                """dc_\w+\(\[([^\]]+)\]\)"""
                            ).find(
                                decodedJs
                            )

                        if (
                            partsMatch != null
                        ) {

                            val arrayContent =
                                partsMatch
                                    .groupValues[1]

                            /*
                             * Kaynak scraper:
                             *
                             * partsMatch[1]
                             * .match(/"([^"]+)"/g)
                             */
                            val parts =
                                Regex(
                                    """"([^"]+)""""
                                )
                                    .findAll(
                                        arrayContent
                                    )
                                    .map {
                                        it.groupValues[1]
                                    }
                                    .toList()

                            if (
                                parts.isNotEmpty()
                            ) {

                                val videoUrl =
                                    decodeVideoUrl(
                                        parts
                                    )

                                if (
                                    videoUrl != null
                                ) {

                                    /*
                                     * Çalışan iframe'in origin'i.
                                     *
                                     * Rapidrame'de kritik.
                                     */
                                    val uri =
                                        java.net.URI(
                                            iframeUrl
                                        )

                                    val origin =
                                        if (
                                            !uri.scheme
                                                .isNullOrBlank() &&
                                            !uri.host
                                                .isNullOrBlank()
                                        ) {
                                            "${uri.scheme}://${uri.host}"
                                        } else {
                                            "https://www.hdfilmcehennemi.ws"
                                        }

                                    return Pair(
                                        videoUrl,
                                        origin
                                    )
                                }
                            }
                        }
                    }

                    /*
                     * =================================================
                     * JSON-LD fallback
                     * =================================================
                     */
                    val jsonLdMatch =
                        Regex(
                            """<script type=["']application/ld\+json["']>([\s\S]*?)</script>""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        ).find(
                            html
                        )

                    if (
                        jsonLdMatch != null
                    ) {

                        val jsonLd =
                            jsonLdMatch
                                .groupValues[1]

                        val contentUrlMatch =
                            Regex(
                                """"contentUrl"\s*:\s*"([^"]+)"""",
                                setOf(
                                    RegexOption.IGNORE_CASE
                                )
                            ).find(
                                jsonLd
                            )

                        if (
                            contentUrlMatch != null
                        ) {

                            val contentUrl =
                                contentUrlMatch
                                    .groupValues[1]

                            if (
                                isValidVideoUrl(
                                    contentUrl
                                )
                            ) {

                                val uri =
                                    java.net.URI(
                                        iframeUrl
                                    )

                                val origin =
                                    if (
                                        !uri.scheme
                                            .isNullOrBlank() &&
                                        !uri.host
                                            .isNullOrBlank()
                                    ) {
                                        "${uri.scheme}://${uri.host}"
                                    } else {
                                        "https://www.hdfilmcehennemi.ws"
                                    }

                                return Pair(
                                    contentUrl,
                                    origin
                                )
                            }
                        }
                    }

                    null

                } catch (
                    error: Exception
                ) {

                    println(
                        "HDFilmCehennemi iframe çözme hatası: " +
                            "${error::class.simpleName}: " +
                            error.message
                    )

                    null
                }
            }

            /*
             * ====================================================
             * 1. Önce sayfadaki iframe
             * ====================================================
             */
            var result =
                scrapeIframe(
                    primaryIframe
                )

            var usedIframe =
                primaryIframe

            /*
             * ====================================================
             * 2. Alternatif kaynaklar
             * ====================================================
             *
             * Güncel scraper:
             *
             * if alt.active continue
             *
             * Rapidrame:
             *
             * EMBED_BASE/video/embed/{videoId}/
             * ?rapidrame_id={alt.videoId}
             * ====================================================
             */
            if (
                result == null
            ) {

                val videoId =
                    Regex(
                        """embed/([^/\?]+)"""
                    )
                        .find(
                            primaryIframe
                        )
                        ?.groupValues
                        ?.getOrNull(1)

                if (
                    !videoId.isNullOrBlank()
                ) {

                    for (
                        alternative
                        in alternatives
                    ) {

                        /*
                         * Güncel scraper aktif source'u
                         * alternatif olarak tekrar denemiyor.
                         */
                        if (
                            alternative.active
                        ) {
                            continue
                        }

                        val alternativeUrl =
                            if (
                                alternative.name
                                    .equals(
                                        "Rapidrame",
                                        ignoreCase = true
                                    ) &&
                                !alternative.videoId
                                    .isNullOrBlank()
                            ) {

                                "https://hdfilmcehennemi.mobi" +
                                    "/video/embed/" +
                                    videoId +
                                    "/?rapidrame_id=" +
                                    alternative.videoId

                            } else {

                                "https://hdfilmcehennemi.mobi" +
                                    "/video/embed/" +
                                    videoId +
                                    "/"
                            }

                        val alternativeResult =
                            scrapeIframe(
                                alternativeUrl
                            )

                        if (
                            alternativeResult != null
                        ) {

                            result =
                                alternativeResult

                            usedIframe =
                                alternativeUrl

                            break
                        }
                    }
                }
            }

            /*
             * ====================================================
             * Sonuç yok
             * ====================================================
             */
            if (
                result == null
            ) {

                println(
                    "HDFilmCehennemi: Video URL bulunamadı -> $pageUrl"
                )

                return false
            }

            val videoUrl =
                result.first

            /*
             * ====================================================
             * ÇALIŞAN IFRAME'İN ORIGIN'I
             * ====================================================
             *
             * Güncel scraper:
             *
             * result.embedOrigin =
             * getEmbedOrigin(usedIframeSrc)
             *
             * ardından:
             *
             * Referer = embedOrigin + "/"
             * Origin  = embedOrigin
             *
             * Bu özellikle Rapidrame için kritik.
             * ====================================================
             */
            val usedUri =
                java.net.URI(
                    usedIframe
                )

            val embedOrigin =
                if (
                    !usedUri.scheme
                        .isNullOrBlank() &&
                    !usedUri.host
                        .isNullOrBlank()
                ) {

                    "${usedUri.scheme}://${usedUri.host}"

                } else {

                    "https://www.hdfilmcehennemi.mobi"
                }

            val streamReferer =
                "$embedOrigin/"

            /*
             * ====================================================
             * CloudStream stream
             * ====================================================
             */
            callback(
                newExtractorLink(
                    source = this.name,
                    name =
                        if (
                            usedIframe.contains(
                                "rapidrame_id=",
                                ignoreCase = true
                            )
                        ) {
                            "Rapidrame"
                        } else {
                            "HDFilmCehennemi"
                        },
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {

                    referer =
                        streamReferer

                    quality =
                        Qualities.Unknown.value

                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to streamReferer,
                        "Origin" to embedOrigin
                    )
                }
            )

            true

        } catch (error: Exception) {

            logError(
                "Rapidrame bağlantısı alınamadı: $data",
                error
            )

            false
        }
    }

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

        return url.startsWith(
            "$mainUrl/"
        )
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
                "${error::class.simpleName}: " +
                error.message
        )
    }
}
