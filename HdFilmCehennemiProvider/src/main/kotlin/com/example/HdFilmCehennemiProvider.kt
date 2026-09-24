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
     * DİNAMİK RAPIDRAME / HLS
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

            val pageDocument =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            /*
             * Sayfadaki iframe'leri sırayla al.
             *
             * Sadece ilk iframe'e bağlı kalmıyoruz.
             */
            val iframeElements =
                pageDocument.select(
                    "iframe[src], iframe[data-src]"
                )

            if (iframeElements.isEmpty()) {

                println(
                    "HDFilmCehennemi: iframe bulunamadı -> $pageUrl"
                )

                return false
            }

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
             * CHARACTER UNMIX
             * ====================================================
             */
            fun characterUnmix(
                value: String
            ): String {

                val output =
                    StringBuilder()

                for (i in value.indices) {

                    val charCode =
                        value[i].code

                    val shift =
                        (
                            399756995L %
                                (i + 5).toLong()
                            ).toInt()

                    val unmixed =
                        (
                            charCode -
                                shift +
                                256
                            ) % 256

                    output.append(
                        unmixed.toChar()
                    )
                }

                return output.toString()
            }

            /*
             * ====================================================
             * BASE64
             * ====================================================
             */
            fun base64Decode(
                value: String
            ): String {

                val cleaned =
                    value
                        .replace(
                            "\\/",
                            "/"
                        )
                        .replace(
                            "\\u002F",
                            "/",
                            ignoreCase = true
                        )
                        .trim()

                val bytes =
                    android.util.Base64.decode(
                        cleaned,
                        android.util.Base64.DEFAULT
                    )

                return bytes.toString(
                    Charsets.ISO_8859_1
                )
            }

            /*
             * ====================================================
             * URL TEMİZLEME
             * ====================================================
             */
            fun cleanVideoUrl(
                raw: String?
            ): String? {

                if (raw.isNullOrBlank()) {
                    return null
                }

                var value =
                    raw.trim()

                value =
                    value
                        .replace(
                            "\\/",
                            "/"
                        )
                        .replace(
                            "&amp;",
                            "&"
                        )
                        .replace(
                            "\\u0026",
                            "&"
                        )
                        .replace(
                            "\\u003D",
                            "="
                        )
                        .replace(
                            "\\u002F",
                            "/"
                        )

                value =
                    value
                        .trim(
                            '"',
                            '\'',
                            '`',
                            ' ',
                            '\n',
                            '\r',
                            '\t'
                        )

                return value
            }

            /*
             * ====================================================
             * VIDEO URL KONTROLÜ
             * ====================================================
             */
            fun isValidVideoUrl(
                url: String?
            ): Boolean {

                val value =
                    cleanVideoUrl(url)

                if (value.isNullOrBlank()) {
                    return false
                }

                return (
                    value.startsWith(
                        "https://"
                    ) ||
                        value.startsWith(
                            "http://"
                        )
                    ) &&
                    (
                        value.contains(
                            ".m3u8",
                            ignoreCase = true
                        ) ||
                            value.contains(
                                "/hls/",
                                ignoreCase = true
                            ) ||
                            value.contains(
                                ".mp4",
                                ignoreCase = true
                            )
                        )
            }

            /*
             * ====================================================
             * HERHANGİ BİR M3U8 URL'SİNİ HTML / JS İÇİNDEN BUL
             *
             * Bu bölüm /rplayer sayfası için özellikle önemli.
             * ====================================================
             */
            fun findVideoUrlInText(
                text: String
            ): String? {

                if (text.isBlank()) {
                    return null
                }

                val normalized =
                    text
                        .replace(
                            "\\/",
                            "/"
                        )
                        .replace(
                            "&amp;",
                            "&"
                        )
                        .replace(
                            "\\u002F",
                            "/"
                        )
                        .replace(
                            "\\u0026",
                            "&"
                        )

                val patterns =
                    listOf(

                        /*
                         * Tam URL
                         */
                        Regex(
                            """https?://[^"'`<>\s\\]+\.m3u8(?:\?[^"'`<>\s\\]*)?""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        ),

                        /*
                         * JSON / JS içinde file: "..."
                         */
                        Regex(
                            """(?:file|src|url|source)\s*[:=]\s*["'](https?://[^"'\\]+\.m3u8(?:\?[^"'\\]*)?)["']""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        ),

                        /*
                         * Sadece URL'nin escape edilmemiş parçası
                         */
                        Regex(
                            """(https?://[^"'`\s<>]+\.m3u8[^"'`\s<>]*)""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        )
                    )

                for (pattern in patterns) {

                    val match =
                        pattern
                            .find(normalized)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?: pattern
                                .find(normalized)
                                ?.value

                    val cleaned =
                        cleanVideoUrl(match)

                    if (
                        isValidVideoUrl(
                            cleaned
                        )
                    ) {
                        return cleaned
                    }
                }

                return null
            }

            /*
             * ====================================================
             * JS PACKER
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
             * DECODE VARIANT 1
             *
             * reverse -> ROT13 -> Base64 -> unmix
             * ====================================================
             */
            fun decodeVariant1(
                value: String
            ): String {

                var result =
                    value.reversed()

                result =
                    rot13(
                        result
                    )

                result =
                    base64Decode(
                        result
                    )

                return characterUnmix(
                    result
                )
            }

            /*
             * ====================================================
             * DECODE VARIANT 2
             *
             * reverse -> Base64 -> ROT13 -> unmix
             * ====================================================
             */
            fun decodeVariant2(
                value: String
            ): String {

                var result =
                    value.reversed()

                result =
                    base64Decode(
                        result
                    )

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
             * DECODE VARIANT 3
             *
             * Base64 -> reverse -> ROT13 -> unmix
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
                    result.reversed()

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
             * VIDEO URL DECODE
             * ====================================================
             */
            fun decodeVideoUrl(
                parts: List<String>
            ): String? {

                val value =
                    parts.joinToString("")

                try {

                    val result =
                        decodeVariant3(
                            value
                        )

                    val cleaned =
                        cleanVideoUrl(
                            result
                        )

                    if (
                        isValidVideoUrl(
                            cleaned
                        )
                    ) {
                        return cleaned
                    }

                } catch (
                    ignored: Exception
                ) {
                }

                try {

                    val result =
                        decodeVariant1(
                            value
                        )

                    val cleaned =
                        cleanVideoUrl(
                            result
                        )

                    if (
                        isValidVideoUrl(
                            cleaned
                        )
                    ) {
                        return cleaned
                    }

                } catch (
                    ignored: Exception
                ) {
                }

                try {

                    val result =
                        decodeVariant2(
                            value
                        )

                    val cleaned =
                        cleanVideoUrl(
                            result
                        )

                    if (
                        isValidVideoUrl(
                            cleaned
                        )
                    ) {
                        return cleaned
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
             */
            suspend fun scrapeIframe(
                iframeUrl: String
            ): String? {

                return try {

                    val isRapidPlayer =
                        iframeUrl.contains(
                            "/rplayer/",
                            ignoreCase = true
                        ) ||
                            iframeUrl.contains(
                                "rapidrame",
                                ignoreCase = true
                            )

                    /*
                     * Rapidrame/rplayer için kaynak sitenin
                     * .ws referer'ı kullanılıyor.
                     */
                    val iframeReferer =
                        if (isRapidPlayer) {
                            "https://www.hdfilmcehennemi.ws/"
                        } else {
                            "$mainUrl/"
                        }

                    val iframeHeaders =
                        mapOf(
                            "User-Agent" to userAgent,
                            "Accept" to
                                "text/html,application/xhtml+xml," +
                                "application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language" to
                                "tr-TR,tr;q=0.9,en;q=0.8",
                            "Referer" to iframeReferer
                        )

                    val iframeResponse =
                        app.get(
                            iframeUrl,
                            headers = iframeHeaders
                        )

                    val html =
                        iframeResponse.text

                    if (html.isBlank()) {
                        return null
                    }

                    /*
                     * ------------------------------------------------
                     * 1. HTML içinde doğrudan M3U8
                     * ------------------------------------------------
                     */
                    val directM3u8 =
                        findVideoUrlInText(
                            html
                        )

                    if (
                        directM3u8 != null
                    ) {
                        return directM3u8
                    }

                    /*
                     * ------------------------------------------------
                     * 2. Packed JS
                     * ------------------------------------------------
                     */
                    val packedRegex =
                        Regex(
                            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+)',(\d+),(\d+),'([^']+)'"",
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

                        val keywords =
                            packedMatch
                                .groupValues[4]

                        val decodedJs =
                            unpackJs(
                                packedCode,
                                base,
                                keywords
                            )

                        /*
                         * Packed JS açıldıktan sonra doğrudan
                         * m3u8 aranıyor.
                         */
                        val decodedDirect =
                            findVideoUrlInText(
                                decodedJs
                            )

                        if (
                            decodedDirect != null
                        ) {
                            return decodedDirect
                        }

                        /*
                         * dc_xxx(["..."])
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

                            val parts =
                                Regex(
                                    """"([^"]+)""""
                                )
                                    .findAll(
                                        partsMatch
                                            .groupValues[1]
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
                                    return videoUrl
                                }
                            }
                        }
                    }

                    /*
                     * ------------------------------------------------
                     * 3. JSON-LD
                     * ------------------------------------------------
                     */
                    val jsonLdMatch =
                        Regex(
                            """<script[^>]+type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        )
                            .find(
                                html
                            )

                    if (
                        jsonLdMatch != null
                    ) {

                        val jsonLd =
                            jsonLdMatch
                                .groupValues[1]

                        val contentUrl =
                            Regex(
                                """"contentUrl"\s*:\s*"([^"]+)"""",
                                setOf(
                                    RegexOption.IGNORE_CASE
                                )
                            )
                                .find(
                                    jsonLd
                                )
                                ?.groupValues
                                ?.getOrNull(1)

                        val cleaned =
                            cleanVideoUrl(
                                contentUrl
                            )

                        if (
                            isValidVideoUrl(
                                cleaned
                            )
                        ) {
                            return cleaned
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
             * IFRAME'LERİ SIRAYLA DENE
             * ====================================================
             */
            var videoUrl: String? = null

            var usedIframe: String? = null

            for (
                iframeElement in iframeElements
            ) {

                val rawIframe =
                    firstNonBlank(
                        iframeElement.attr("src"),
                        iframeElement.attr("data-src")
                    )

                if (
                    rawIframe.isNullOrBlank()
                ) {
                    continue
                }

                val currentIframe =
                    normalizeUrl(
                        rawIframe
                    )

                println(
                    "HDFilmCehennemi: iframe deneniyor -> " +
                        currentIframe
                )

                val currentVideo =
                    scrapeIframe(
                        currentIframe
                    )

                if (
                    currentVideo != null
                ) {

                    videoUrl =
                        currentVideo

                    usedIframe =
                        currentIframe

                    break
                }
            }

            /*
             * ====================================================
             * ALTERNATİF PLAYER FALLBACK
             * ====================================================
             */
            if (
                videoUrl == null
            ) {

                val primaryIframe =
                    iframeElements
                        .firstOrNull()
                        ?.let {
                            firstNonBlank(
                                it.attr("src"),
                                it.attr("data-src")
                            )
                        }

                val videoId =
                    primaryIframe
                        ?.let {
                            Regex(
                                """embed/([^/?]+)"""
                            )
                                .find(it)
                                ?.groupValues
                                ?.getOrNull(1)
                        }

                if (
                    !videoId.isNullOrBlank()
                ) {

                    for (
                        alternative
                        in alternatives
                    ) {

                        if (
                            alternative.active
                        ) {
                            continue
                        }

                        if (
                            alternative.videoId
                                .isNullOrBlank()
                        ) {
                            continue
                        }

                        val alternativeUrl =
                            if (
                                alternative.name.equals(
                                    "Rapidrame",
                                    ignoreCase = true
                                )
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

                        println(
                            "HDFilmCehennemi: alternatif deneniyor -> " +
                                alternativeUrl
                        )

                        val alternativeResult =
                            scrapeIframe(
                                alternativeUrl
                            )

                        if (
                            alternativeResult != null
                        ) {

                            videoUrl =
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
             * VIDEO BULUNAMADI
             * ====================================================
             */
            if (
                videoUrl.isNullOrBlank()
            ) {

                println(
                    "HDFilmCehennemi: Video URL bulunamadı -> $pageUrl"
                )

                return false
            }

            /*
             * ====================================================
             * URL TEMİZLE VE NULLABLE TİPİ KESİNLEŞTİR
             *
             * Buradaki önemli düzeltme:
             *
             * cleanVideoUrl() String? döndürüyor.
             * Bu nedenle sonucu doğrudan videoUrl içine koymak
             * yerine kesin olarak String olan resolvedVideoUrl
             * oluşturuyoruz.
             * ====================================================
             */
            val resolvedVideoUrl: String =
                cleanVideoUrl(
                    videoUrl
                )
                    ?: run {

                        println(
                            "HDFilmCehennemi: Video URL temizlenemedi -> $videoUrl"
                        )

                        return false
                    }

            if (
                !isValidVideoUrl(
                    resolvedVideoUrl
                )
            ) {

                println(
                    "HDFilmCehennemi: Geçersiz video URL -> " +
                        resolvedVideoUrl
                )

                return false
            }

            /*
             * ====================================================
             * RAPIDRAME TESPİTİ
             * ====================================================
             */
            val isRapidrame =
                usedIframe?.contains(
                    "/rplayer/",
                    ignoreCase = true
                ) == true ||
                    usedIframe?.contains(
                        "rapidrame",
                        ignoreCase = true
                    ) == true ||
                    usedIframe?.contains(
                        "rapidrame_id=",
                        ignoreCase = true
                    ) == true ||
                    resolvedVideoUrl.contains(
                        "rapidrame.com",
                        ignoreCase = true
                    )

            /*
             * ====================================================
             * STREAM HEADER'LARI
             * ====================================================
             */
            val streamReferer: String

            val streamOrigin: String

            if (
                isRapidrame
            ) {

                /*
                 * Rapidrame için özellikle .ws.
                 */
                streamReferer =
                    "https://www.hdfilmcehennemi.ws/"

                streamOrigin =
                    "https://www.hdfilmcehennemi.ws"

            } else {

                streamReferer =
                    "https://hdfilmcehennemi.mobi/"

                streamOrigin =
                    "https://hdfilmcehennemi.mobi"
            }

            println(
                "HDFilmCehennemi: video bulundu"
            )

            println(
                "HDFilmCehennemi: iframe = $usedIframe"
            )

            println(
                "HDFilmCehennemi: m3u8 = $resolvedVideoUrl"
            )

            println(
                "HDFilmCehennemi: rapidrame = $isRapidrame"
            )

            println(
                "HDFilmCehennemi: referer = $streamReferer"
            )

            println(
                "HDFilmCehennemi: origin = $streamOrigin"
            )

            /*
             * ====================================================
             * CLOUDSTREAM LINK
             *
             * Burada artık nullable videoUrl değil,
             * kesin olarak String olan resolvedVideoUrl
             * kullanılıyor.
             * ====================================================
             */
            callback(
                newExtractorLink(
                    source = this.name,
                    name =
                        if (
                            isRapidrame
                        ) {
                            "Rapidrame HLS"
                        } else {
                            "HDFilmCehennemi HLS"
                        },
                    url = resolvedVideoUrl,
                    type = ExtractorLinkType.M3U8
                ) {

                    referer =
                        streamReferer

                    quality =
                        Qualities.Unknown.value

                    headers =
                        mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to streamReferer,
                            "Origin" to streamOrigin,
                            "Accept" to "*/*"
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
