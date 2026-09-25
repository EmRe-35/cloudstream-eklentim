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

        val currentPage = page.coerceAtLeast(1)
        val baseUrl = normalizeUrl(request.data)

        val pageUrl =
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

            val document =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            val results =
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

        val encodedQuery =
            URLEncoder
                .encode(
                    query.trim(),
                    "UTF-8"
                )
                .replace(
                    "+",
                    "%20"
                )

        val searchUrls =
            listOf(
                "$mainUrl/search/$encodedQuery/",
                "$mainUrl/?s=$encodedQuery"
            )

        for (searchUrl in searchUrls) {

            try {

                val document =
                    app.get(
                        searchUrl,
                        headers = headers
                    ).document

                val results =
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

        val results =
            mutableListOf<SearchResponse>()

        val posterElements =
            document.select(
                "a.poster[href]"
            )

        for (element in posterElements) {

            val result =
                element.toSearchResult()

            if (result != null) {
                results.add(result)
            }
        }

        if (results.isEmpty()) {

            val fallbackSelector =
                "article," +
                    ".movie-box," +
                    ".movie-item," +
                    ".film-box," +
                    ".film-item," +
                    ".card"

            for (
                element in document.select(
                    fallbackSelector
                )
            ) {

                val result =
                    element.toSearchResult()

                if (result != null) {
                    results.add(result)
                }
            }
        }

        return results.distinctBy {
            it.url
        }
    }

    private fun Element.toSearchResult():
        SearchResponse? {

        val linkElement =
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

        val contentUrl =
            normalizeUrl(
                linkElement.attr("href")
            )

        if (!isValidContentUrl(contentUrl)) {
            return null
        }

        val imageElement =
            findElement(
                this,
                "img"
            )

        val title =
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

        val posterUrl =
            imageElement?.let { image ->

                val rawPoster =
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

        val type =
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

        val pageUrl =
            normalizeUrl(url)

        return try {

            val document =
                app.get(
                    pageUrl,
                    headers = headers
                ).document

            val title =
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

            val posterElement =
                findElement(
                    document,
                    "meta[property=og:image]," +
                        "div.poster img," +
                        ".poster img," +
                        ".movie-poster img," +
                        "article img"
                )

            val poster =
                if (posterElement == null) {
                    null
                } else {

                    val rawPoster =
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

            val plot =
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

                val episodes =
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

        val selector =
            ".episode-list a[href]," +
                ".episodes a[href]," +
                "ul.episodes a[href]," +
                ".season-list a[href]," +
                ".seasons-list a[href]," +
                "a[href*='/bolum']," +
                "a[href*='-bolum-']"

        val episodes =
            mutableListOf<Episode>()

        for (
            element in document.select(selector)
        ) {

            val episodeUrl =
                normalizeUrl(
                    element.attr("href")
                )

            if (!isValidContentUrl(episodeUrl)) {
                continue
            }

            val episodeName =
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
            it.data
        }
    }

    /*
     * ============================================================
     * LOAD LINKS
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
                    .select(".alternative-link")
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
             * ========================================================
             * ROT-N
             * ========================================================
             */

            fun rotN(
                value: String,
                shift: Int
            ): String {

                val normalizedShift =
                    ((shift % 26) + 26) % 26

                return value.map { c ->

                    when {

                        c in 'a'..'z' -> {
                            (
                                (
                                    c.code -
                                        'a'.code +
                                        normalizedShift
                                    ) % 26 +
                                    'a'.code
                                ).toChar()
                        }

                        c in 'A'..'Z' -> {
                            (
                                (
                                    c.code -
                                        'A'.code +
                                        normalizedShift
                                    ) % 26 +
                                    'A'.code
                                ).toChar()
                        }

                        else -> c
                    }

                }.joinToString("")
            }

            /*
             * ========================================================
             * BASE64
             * ========================================================
             */

            fun base64Decode(
                value: String
            ): String {

                val cleaned =
                    value
                        .replace("\\/", "/")
                        .replace(
                            "\\u002F",
                            "/",
                            ignoreCase = true
                        )
                        .replace(
                            "\\u003D",
                            "=",
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
             * ========================================================
             * URL TEMİZLEME
             * ========================================================
             */

            fun cleanVideoUrl(
                raw: String?
            ): String? {

                if (raw.isNullOrBlank()) {
                    return null
                }

                return raw
                    .trim()
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
                    .replace("\\u0026", "&")
                    .replace("\\u003D", "=")
                    .replace("\\u002F", "/")
                    .trim(
                        '"',
                        '\'',
                        '`',
                        ' ',
                        '\n',
                        '\r',
                        '\t'
                    )
            }

            /*
             * ========================================================
             * URL KONTROLÜ
             * ========================================================
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
                    value.startsWith("https://") ||
                        value.startsWith("http://")
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
             * ========================================================
             * HERHANGİ BİR M3U8 BUL
             * ========================================================
             */

            fun findVideoUrlInText(
                text: String
            ): String? {

                if (text.isBlank()) {
                    return null
                }

                val normalized =
                    text
                        .replace("\\/", "/")
                        .replace("&amp;", "&")
                        .replace("\\u002F", "/")
                        .replace("\\u0026", "&")
                        .replace("\\u003D", "=")

                val patterns =
                    listOf(

                        Regex(
                            """https?://[^"'`<>\s\\]+\.m3u8(?:\?[^"'`<>\s\\]*)?""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        ),

                        Regex(
                            """(?:file|src|url|source)\s*[:=]\s*["'](https?://[^"'\\]+\.m3u8(?:\?[^"'\\]*)?)["']""",
                            setOf(
                                RegexOption.IGNORE_CASE
                            )
                        ),

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

                    if (isValidVideoUrl(cleaned)) {
                        return cleaned
                    }
                }

                return null
            }

            /*
             * ========================================================
             * CHARACTER UNMIX
             * ========================================================
             */

            fun characterUnmix(
                value: String,
                magic: Long = 399756995L,
                offset: Int = 5
            ): String {

                val output =
                    StringBuilder()

                for (i in value.indices) {

                    val charCode =
                        value[i].code

                    val shift =
                        (
                            magic %
                                (i + offset).toLong()
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
             * ========================================================
             * ROLLING XOR
             *
             * Güncel decoder formatında kullanılıyor.
             * ========================================================
             */

            fun rollingXor(
                value: String,
                seed: Int,
                increment: Int
            ): String {

                var accumulator =
                    seed

                val output =
                    StringBuilder()

                for (char in value) {

                    val byte =
                        char.code

                    accumulator =
                        (
                            accumulator +
                                increment
                            ) % 256

                    output.append(
                        (
                            byte xor
                                accumulator
                            ).toChar()
                    )

                    accumulator =
                        (
                            accumulator +
                                byte
                            ) % 256
                }

                return output.toString()
            }

            /*
             * ========================================================
             * INLINE DECODER
             * ========================================================
             */

            data class DecodeStep(
                val type: String,
                val value1: Int = 0,
                val value2: Int = 0
            )

            data class InlineDecoder(
                val steps: List<DecodeStep>,
                val parts: List<String>
            )

            /*
             * Fonksiyon gövdesini dengeli süslü parantezlerle bul.
             * Böylece decoder içerisinde for/if blokları olduğunda
             * regex'in erken bitmesini engelliyoruz.
             */
            fun extractFunctionBody(
                html: String,
                functionStart: Int
            ): String? {

                val openBrace =
                    html.indexOf(
                        '{',
                        functionStart
                    )

                if (openBrace < 0) {
                    return null
                }

                var depth = 0

                for (
                    i in openBrace until html.length
                ) {

                    when (html[i]) {

                        '{' -> {
                            depth++
                        }

                        '}' -> {

                            depth--

                            if (depth == 0) {

                                return html.substring(
                                    openBrace + 1,
                                    i
                                )
                            }
                        }
                    }
                }

                return null
            }

            fun parseInlineDecoders(
                html: String
            ): List<InlineDecoder> {

                val decoders =
                    mutableListOf<InlineDecoder>()

                val functionRegex =
                    Regex(
                        """function\s+(dc_\w+)\s*\(\s*[\w$]+\s*\)\s*\{"""
                    )

                for (
                    functionMatch in
                    functionRegex.findAll(html)
                ) {

                    val functionName =
                        functionMatch
                            .groupValues[1]

                    val body =
                        extractFunctionBody(
                            html,
                            functionMatch.range.first
                        ) ?: continue

                    /*
                     * dc_xxx(["...","..."])
                     */
                    val callRegex =
                        Regex(
                            Regex.escape(functionName) +
                                """\s*\(\s*\[([^\]]+)\]\s*\)"""
                        )

                    val callMatch =
                        callRegex.find(html)
                            ?: continue

                    val parts =
                        Regex(
                            """["']([^"']+)["']"""
                        )
                            .findAll(
                                callMatch.groupValues[1]
                            )
                            .map {
                                it.groupValues[1]
                            }
                            .toList()

                    if (parts.isEmpty()) {
                        continue
                    }

                    val steps =
                        mutableListOf<DecodeStep>()

                    /*
                     * Adımları gövdedeki sıralarına göre buluyoruz.
                     */
                    data class Candidate(
                        val position: Int,
                        val step: DecodeStep
                    )

                    val candidates =
                        mutableListOf<Candidate>()

                    /*
                     * Base64
                     */
                    val base64Regex =
                        Regex(
                            """=\s*atob\(\s*result\s*\)"""
                        )

                    for (
                        match in base64Regex.findAll(body)
                    ) {

                        candidates.add(
                            Candidate(
                                match.range.first,
                                DecodeStep("base64")
                            )
                        )
                    }

                    /*
                     * Reverse
                     */
                    val reverseRegex =
                        Regex(
                            """result\.split\(['"]['"]\)\.reverse\(\)\.join\(['"]['"]\)"""
                        )

                    for (
                        match in reverseRegex.findAll(body)
                    ) {

                        candidates.add(
                            Candidate(
                                match.range.first,
                                DecodeStep("reverse")
                            )
                        )
                    }

                    /*
                     * ROT-N
                     */
                    val rotRegex =
                        Regex(
                            """\(\s*o\s*-\s*base\s*\+\s*(\d+)\s*\)\s*%\s*26"""
                        )

                    for (
                        match in rotRegex.findAll(body)
                    ) {

                        val shift =
                            match
                                .groupValues
                                .getOrNull(1)
                                ?.toIntOrNull()
                                ?: continue

                        candidates.add(
                            Candidate(
                                match.range.first,
                                DecodeStep(
                                    type = "rot",
                                    value1 = shift
                                )
                            )
                        )
                    }

                    /*
                     * Character unmix.
                     *
                     * Hem yeni hem eski formu destekle.
                     */
                    val unmixRegex =
                        Regex(
                            """charCode\s*-\s*\(\s*(\d+)\s*%\s*\(\s*i\s*\+\s*(\d+)\s*\)"""
                        )

                    for (
                        match in unmixRegex.findAll(body)
                    ) {

                        val magic =
                            match
                                .groupValues
                                .getOrNull(1)
                                ?.toIntOrNull()
                                ?: continue

                        val offset =
                            match
                                .groupValues
                                .getOrNull(2)
                                ?.toIntOrNull()
                                ?: continue

                        candidates.add(
                            Candidate(
                                match.range.first,
                                DecodeStep(
                                    type = "unmix",
                                    value1 = magic,
                                    value2 = offset
                                )
                            )
                        )
                    }

                    /*
                     * Rolling XOR.
                     */
                    val xorRegex =
                        Regex(
                            """var\s+acc\s*=\s*(\d+)[\s\S]{0,300}?acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)\s*%\s*256"""
                        )

                    for (
                        match in xorRegex.findAll(body)
                    ) {

                        val seed =
                            match
                                .groupValues
                                .getOrNull(1)
                                ?.toIntOrNull()
                                ?: continue

                        val increment =
                            match
                                .groupValues
                                .getOrNull(2)
                                ?.toIntOrNull()
                                ?: continue

                        candidates.add(
                            Candidate(
                                match.range.first,
                                DecodeStep(
                                    type = "xor",
                                    value1 = seed,
                                    value2 = increment
                                )
                            )
                        )
                    }

                    candidates.sortBy {
                        it.position
                    }

                    steps.addAll(
                        candidates.map {
                            it.step
                        }
                    )

                    if (steps.isNotEmpty()) {

                        decoders.add(
                            InlineDecoder(
                                steps = steps,
                                parts = parts
                            )
                        )
                    }
                }

                return decoders
            }

            /*
             * ========================================================
             * INLINE DECODER UYGULA
             * ========================================================
             */

            fun applyDecodeSteps(
                parts: List<String>,
                steps: List<DecodeStep>
            ): String {

                var result =
                    parts.joinToString("")

                for (step in steps) {

                    result =
                        when (step.type) {

                            "base64" ->
                                base64Decode(
                                    result
                                )

                            "reverse" ->
                                result.reversed()

                            "rot" ->
                                rotN(
                                    result,
                                    step.value1
                                )

                            "unmix" ->
                                characterUnmix(
                                    result,
                                    step.value1.toLong(),
                                    step.value2
                                )

                            "xor" ->
                                rollingXor(
                                    result,
                                    step.value1,
                                    step.value2
                                )

                            else ->
                                result
                        }
                }

                return result
            }

            /*
             * ========================================================
             * LEGACY JS PACKER
             * ========================================================
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

                    for (character in word) {

                        when {

                            character.isDigit() -> {
                                number =
                                    number * base +
                                        character.digitToInt()
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
                        dictionary[number].isNotEmpty()
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
                ) {
                    decodeWord(
                        it.value
                    )
                }
            }

            /*
             * ========================================================
             * ESKİ DECODE VARYANTLARI
             * ========================================================
             */

            fun decodeVariant1(
                value: String
            ): String {

                var result =
                    value.reversed()

                result =
                    rotN(
                        result,
                        13
                    )

                result =
                    base64Decode(
                        result
                    )

                return characterUnmix(
                    result
                )
            }

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
                    rotN(
                        result,
                        13
                    )

                return characterUnmix(
                    result
                )
            }

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
                    rotN(
                        result,
                        13
                    )

                return characterUnmix(
                    result
                )
            }

            fun decodeVideoUrl(
                parts: List<String>
            ): String? {

                val value =
                    parts.joinToString("")

                val variants =
                    listOf(
                        {
                            decodeVariant3(value)
                        },
                        {
                            decodeVariant1(value)
                        },
                        {
                            decodeVariant2(value)
                        }
                    )

                for (decoder in variants) {

                    try {

                        val result =
                            cleanVideoUrl(
                                decoder()
                            )

                        if (
                            isValidVideoUrl(result)
                        ) {
                            return result
                        }

                    } catch (
                        ignored: Exception
                    ) {
                    }
                }

                return null
            }

            /*
             * ========================================================
             * IFRAME SCRAPER
             * ========================================================
             */

            suspend fun scrapeIframe(
                iframeUrl: String
            ): String? {

                return try {

                    val iframeIsRapid =
                        iframeUrl.contains(
                            "/rplayer/",
                            ignoreCase = true
                        ) ||
                            iframeUrl.contains(
                                "rapidrame",
                                ignoreCase = true
                            )

                    /*
                     * Ana site referer'ı ile iframe'i al.
                     */
                    val iframeReferer =
                        "$mainUrl/"

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

                    println(
                        "HDFilmCehennemi: iframe GET -> $iframeUrl"
                    )

                    val response =
                        app.get(
                            iframeUrl,
                            headers = iframeHeaders
                        )

                    val html =
                        response.text

                    if (html.isBlank()) {
                        return null
                    }

                    /*
                     * ------------------------------------------------
                     * 1. Doğrudan M3U8
                     * ------------------------------------------------
                     */

                    findVideoUrlInText(
                        html
                    )?.let {
                        println(
                            "HDFilmCehennemi: doğrudan m3u8 bulundu"
                        )
                        return it
                    }

                    /*
                     * ------------------------------------------------
                     * 2. YENİ INLINE DC DECODER
                     * ------------------------------------------------
                     */

                    val inlineDecoders =
                        parseInlineDecoders(
                            html
                        )

                    println(
                        "HDFilmCehennemi: inline decoder sayısı = " +
                            inlineDecoders.size
                    )

                    for (
                        decoder in inlineDecoders
                    ) {

                        try {

                            val decoded =
                                applyDecodeSteps(
                                    decoder.parts,
                                    decoder.steps
                                )

                            val cleaned =
                                cleanVideoUrl(
                                    decoded
                                )

                            if (
                                isValidVideoUrl(
                                    cleaned
                                )
                            ) {

                                println(
                                    "HDFilmCehennemi: inline decoder m3u8 bulundu -> " +
                                        cleaned
                                )

                                return cleaned
                            }

                        } catch (error: Exception) {

                            println(
                                "HDFilmCehennemi: inline decoder hatası -> " +
                                    "${error::class.simpleName}: " +
                                    error.message
                            )
                        }
                    }

                    /*
                     * ------------------------------------------------
                     * 3. LEGACY PACKED JS
                     * ------------------------------------------------
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

                    if (packedMatch != null) {

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

                        findVideoUrlInText(
                            decodedJs
                        )?.let {
                            return it
                        }

                        val partsMatch =
                            Regex(
                                """dc_\w+\(\[([^\]]+)\]\)"""
                            ).find(
                                decodedJs
                            )

                        if (partsMatch != null) {

                            val parts =
                                Regex(
                                    """["']([^"']+)["']"""
                                )
                                    .findAll(
                                        partsMatch.groupValues[1]
                                    )
                                    .map {
                                        it.groupValues[1]
                                    }
                                    .toList()

                            val decoded =
                                decodeVideoUrl(
                                    parts
                                )

                            if (
                                isValidVideoUrl(
                                    decoded
                                )
                            ) {
                                return decoded
                            }
                        }
                    }

                    /*
                     * ------------------------------------------------
                     * 4. dc_ çağrısını doğrudan HTML içinde ara
                     * ------------------------------------------------
                     */

                    val directPartsRegex =
                        Regex(
                            """dc_\w+\(\[([^\]]+)\]\)"""
                        )

                    val directPartsMatch =
                        directPartsRegex.find(
                            html
                        )

                    if (directPartsMatch != null) {

                        val parts =
                            Regex(
                                """["']([^"']+)["']"""
                            )
                                .findAll(
                                    directPartsMatch.groupValues[1]
                                )
                                .map {
                                    it.groupValues[1]
                                }
                                .toList()

                        val decoded =
                            decodeVideoUrl(
                                parts
                            )

                        if (
                            isValidVideoUrl(
                                decoded
                            )
                        ) {
                            return decoded
                        }
                    }

                    /*
                     * ------------------------------------------------
                     * 5. JSON-LD
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

                    if (jsonLdMatch != null) {

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

                    println(
                        "HDFilmCehennemi: iframe içinde video URL bulunamadı -> " +
                            iframeUrl +
                            " | rapid=" +
                            iframeIsRapid
                    )

                    null

                } catch (error: Exception) {

                    println(
                        "HDFilmCehennemi iframe çözme hatası: " +
                            "${error::class.simpleName}: " +
                            error.message
                    )

                    null
                }
            }

            /*
             * ========================================================
             * IFRAME'LERİ DENE
             * ========================================================
             */

            var videoUrl: String? =
                null

            var usedIframe: String? =
                null

            for (
                iframeElement in iframeElements
            ) {

                val rawIframe =
                    firstNonBlank(
                        iframeElement.attr("src"),
                        iframeElement.attr("data-src")
                    )

                if (rawIframe.isNullOrBlank()) {
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

                if (currentVideo != null) {

                    videoUrl =
                        currentVideo

                    usedIframe =
                        currentIframe

                    break
                }
            }

            /*
             * ========================================================
             * ALTERNATİF PLAYER
             * ========================================================
             */

            if (videoUrl == null) {

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

                if (!videoId.isNullOrBlank()) {

                    for (
                        alternative in alternatives
                    ) {

                        if (alternative.active) {
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
             * ========================================================
             * VIDEO YOK
             * ========================================================
             */

            if (videoUrl.isNullOrBlank()) {

                println(
                    "HDFilmCehennemi: Video URL bulunamadı -> " +
                        pageUrl
                )

                return false
            }

            /*
             * ========================================================
             * NULLABLE -> STRING
             * ========================================================
             */

            val resolvedVideoUrl: String =
                cleanVideoUrl(
                    videoUrl
                ) ?: run {

                    println(
                        "HDFilmCehennemi: Video URL temizlenemedi -> " +
                            videoUrl
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
             * ========================================================
             * KULLANILAN IFRAME'İN ORIGIN'İNİ BUL
             *
             * ÖNEMLİ:
             *
             * Önceden her Rapidrame için .ws kullanıyorduk.
             * Güncel sistemde ise iframe'in gerçek origin'i
             * kullanılmalı.
             * ========================================================
             */

            fun getOrigin(
                url: String?
            ): String {

                if (url.isNullOrBlank()) {
                    return mainUrl
                }

                return try {

                    val match =
                        Regex(
                            """^(https?://[^/]+)"""
                        ).find(url)

                    match
                        ?.groupValues
                        ?.getOrNull(1)
                        ?: mainUrl

                } catch (
                    ignored: Exception
                ) {
                    mainUrl
                }
            }

            val embedOrigin =
                getOrigin(
                    usedIframe
                )

            val streamReferer =
                "$embedOrigin/"

            val streamOrigin =
                embedOrigin

            /*
             * ========================================================
             * RAPIDRAME BİLGİSİ
             * ========================================================
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

            println(
                "HDFilmCehennemi: video bulundu"
            )

            println(
                "HDFilmCehennemi: iframe = " +
                    usedIframe
            )

            println(
                "HDFilmCehennemi: m3u8 = " +
                    resolvedVideoUrl
            )

            println(
                "HDFilmCehennemi: rapidrame = " +
                    isRapidrame
            )

            println(
                "HDFilmCehennemi: embedOrigin = " +
                    embedOrigin
            )

            println(
                "HDFilmCehennemi: referer = " +
                    streamReferer
            )

            println(
                "HDFilmCehennemi: origin = " +
                    streamOrigin
            )

            /*
             * ========================================================
             * CLOUDSTREAM STREAM
             * ========================================================
             */

            val streamHeaders =
    mapOf(
        "User-Agent" to userAgent,
        "Referer" to streamReferer,
        "Origin" to streamOrigin,
        "Accept" to "*/*"
    )

try {

    M3u8Helper
        .generateM3u8(
            name =
                if (isRapidrame) {
                    "Rapidrame HLS"
                } else {
                    "HDFilmCehennemi HLS"
                },
            streamUrl = resolvedVideoUrl,
            referer = streamReferer,
            headers = streamHeaders
        )
        .forEach { extractorLink ->

            callback(
                extractorLink
            )
        }

} catch (error: Exception) {

    println(
        "HDFilmCehennemi: M3U8 generate hatası -> " +
            "${error::class.simpleName}: " +
            error.message
    )

    callback(
        newExtractorLink(
            source = this.name,
            name =
                if (isRapidrame) {
                    "Rapidrame HLS"
                } else {
                    "HDFilmCehennemi HLS"
                },
            url = resolvedVideoUrl,
            type = ExtractorLinkType.M3U8
        )
    )
}

            true

        } catch (error: Exception) {

            logError(
                "Rapidrame bağlantısı alınamadı: $data",
                error
            )

            false
        }
    }

    private fun findElement(
        document: Document,
        selector: String
    ): Element? {

        return document
            .select(selector)
            .firstOrNull()
    }

    private fun findElement(
        element: Element,
        selector: String
    ): Element? {

        return element
            .select(selector)
            .firstOrNull()
    }

    private fun normalizeUrl(
        rawUrl: String?
    ): String {

        if (rawUrl.isNullOrBlank()) {
            return ""
        }

        val value =
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
