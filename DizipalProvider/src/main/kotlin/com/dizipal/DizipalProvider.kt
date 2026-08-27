package com.dizipal

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DizipalProvider : MainAPI() {
    override var mainUrl = "https://dizipal2302.com"
    override var name = "Dizipal"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    // 1. ANA SAYFA
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document.select("a[href*='/film/'], a[href*='/dizi/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { it.toSearchResult() }
        val homeLists = if (items.isEmpty()) {
            emptyList()
        } else {
            listOf(HomePageList("Öne Çıkanlar", items))
        }

        return newHomePageResponse(homeLists)
    }

    // 2. ARAMA
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/arama?q=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("a[href*='/film/'], a[href*='/dizi/']").distinctBy { it.attr("href") }.mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. DETAY VE BÖLÜM LİSTESİ
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val ldCombined = document.select("script[type=application/ld+json]")
            .joinToString("\n") { it.data() }

        val title = document.selectFirst("h1, .entry-title, .title")?.text()?.trim() ?: "Bilinmeyen Başlık"
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content") ?: document.selectFirst("img")?.attr("src"))
        val descriptionFromLd = Regex("\"description\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])+)\"")
            .findAll(ldCombined)
            .map { match ->
                match.groupValues[1]
                    .replace("\\\\/", "/")
                    .replace("\\u0027", "'")
                    .replace("\\u0026", "&")
                    .trim()
            }
            .firstOrNull { it.length > 40 }
        val description = descriptionFromLd
            ?: document.selectFirst("p.xf19fb0, .xf19fb0, .description p, .overview p")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()

        val year = Regex("\"datePublished\"\\s*:\\s*\"?((?:19|20)\\d{2})\"?")
            .find(ldCombined)?.groupValues?.get(1)?.toIntOrNull()

        val imdbScore = Regex("\"ratingValue\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)\"?")
            .find(ldCombined)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("([0-9]+(?:\\.[0-9]+)?)\\s*IMDB", RegexOption.IGNORE_CASE)
                .find(document.text())?.groupValues?.get(1)?.toDoubleOrNull()

        val durationMinutes = Regex("\"duration\"\\s*:\\s*\"PT(\\d+)M\"", RegexOption.IGNORE_CASE)
            .find(ldCombined)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(\\d+)\\s*dk", RegexOption.IGNORE_CASE)
                .find(document.text())?.groupValues?.get(1)?.toIntOrNull()

        val trailerUrl = document.select("a[href]")
            .firstOrNull {
                val href = it.attr("href")
                val text = it.text()
                text.contains("Fragman", ignoreCase = true) ||
                    href.contains("youtube.com", ignoreCase = true) ||
                    href.contains("youtu.be", ignoreCase = true)
            }
            ?.attr("href")
            ?.let { rawUrl ->
                rawUrl
                    .replace("https://www.youtube.com/embed/", "https://www.youtube.com/watch?v=")
                    .replace("https://youtube.com/embed/", "https://www.youtube.com/watch?v=")
            }

        val episodeElements = document.select("a[href*='/bolum/']")
        val isSeries = url.contains("/dizi/")

        return if (isSeries) {
            val episodes = episodeElements.mapIndexed { index: Int, element: Element ->
                val epUrl = fixUrl(element.attr("href"))
                val epName = element.text().trim()

                val seasonNum = Regex("(\\d+)\\.\\s*Sezon", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("(\\d+)\\.\\s*Bölüm", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

                newEpisode(epUrl) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = imdbScore?.let { Score.from10(it) }
                this.duration = durationMinutes
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = imdbScore?.let { Score.from10(it) }
                this.duration = durationMinutes
                trailerUrl?.let {
                    this.trailers = mutableListOf(
                        TrailerData(
                            extractorUrl = it,
                            referer = url,
                            raw = false
                        )
                    )
                }
            }
        }
    }

    // 4. VİDEO KAYNAKLARI (TÜM YAPI KORUNDU + IMAGESTOO EKLENDİ)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val response = app.get(data)
        val doc = response.document
        val rawHtml = response.text
        
        val iframeLinks = mutableSetOf<String>()

        // 1. data-cfg içindeki Base64 JSON
        doc.select("[data-cfg]").forEach { elem ->
            runCatching {
                val cfg = elem.attr("data-cfg")
                if (cfg.isNotBlank()) {
                    val decoded = String(Base64.decode(cfg, Base64.DEFAULT), Charsets.UTF_8)
                    val json = JSONObject(decoded)
                    json.optString("v").takeIf { it.isNotBlank() }?.let { iframeLinks.add(fixUrl(it)) }
                    json.optString("file").takeIf { it.isNotBlank() }?.let { iframeLinks.add(fixUrl(it)) }
                    json.optString("url").takeIf { it.isNotBlank() }?.let { iframeLinks.add(fixUrl(it)) }
                }
            }
        }

        // 2. Sayfadaki diğer tüm standart iframeler ve embed yapıları
        doc.select("iframe, [data-frame], [data-video], [data-src], [data-url], .sources-list a, .player-options a").forEach {
            val src = it.attr("data-frame").ifEmpty { it.attr("data-video") }
                .ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("data-url") }
                .ifEmpty { it.attr("href") }.ifEmpty { it.attr("src") }
            if (src.isNotBlank() && !src.startsWith("#")) iframeLinks.add(fixUrl(src))
        }

        // 3. Script / HTML içine gizlenmiş iframe/URL kalıpları (Kaba kuvvet)
        Regex("""(?i)(?:src|iframe|file|url)\s*[:=]\s*["'](https?://[^"']+)["']""").findAll(rawHtml).forEach {
            iframeLinks.add(fixUrl(it.groupValues[1]))
        }

        for (iframeUrl in iframeLinks.distinct()) {
            val cleanIframe = iframeUrl.replace("&amp;", "&").trim()
            if (!cleanIframe.startsWith("http")) continue

            // A) Imagestoo'ya özel POST/AJAX yaklaşımlı çözüm
            if (cleanIframe.contains("imagestoo.com/video/")) {
                val hash = cleanIframe.substringAfterLast("/")
                if (hash.isNotBlank()) {
                    val apiUrl = "https://imagestoo.com/player/index.php?data=$hash&do=getVideo"
                    
                    val apiResponse = runCatching {
                        app.post(
                            url = apiUrl,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "*/*",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to "https://imagestoo.com",
                                "Referer" to cleanIframe,
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                            ),
                            data = mapOf(
                                "hash" to hash,
                                "r" to mainUrl
                            )
                        ).text
                    }.getOrNull()

                    if (apiResponse != null) {
                        val videoRegex = Regex("""(?i)(https?:\/\/[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
                        videoRegex.findAll(apiResponse.replace("\\/", "/")).forEach { match ->
                            val finalUrl = match.groupValues[1]
                            val isM3u8 = finalUrl.contains(".m3u8")
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "Imagestoo Server",
                                    url = finalUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = cleanIframe
                                    this.headers = mapOf(
                                        "Origin" to "https://imagestoo.com",
                                        "Referer" to cleanIframe,
                                        "User-Agent" to USER_AGENT
                                    )
                                }
                            )
                            foundLinks = true
                        }
                    }
                }
                continue // Imagestoo işlendi, sonrakine geç
            }

            // B) Doğrudan verilmiş M3U8 veya MP4 ise yakala
            if (cleanIframe.contains(".m3u8") || cleanIframe.contains(".mp4")) {
                val isM3u8 = cleanIframe.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Dizipal Kaynak",
                        url = cleanIframe,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
                foundLinks = true
                continue
            }

            // C) ESKİ SİSTEM KORUMASI: JS Packer, Regex Araması ve Subtitle Çekimi (Diğer sunucular için)
            val embedRes = runCatching {
                app.get(
                    url = cleanIframe, 
                    referer = data, 
                    headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*")
                ).text
            }.getOrNull() ?: continue

            val unpacked = unpackJs(embedRes) ?: embedRes

            val videoRegexes = listOf(
                Regex("""(?i)file\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                Regex("""(?i)source\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                Regex("""(?i)src\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
            )

            var extractedVideo: String? = null
            for (regex in videoRegexes) {
                extractedVideo = regex.find(unpacked)?.groupValues?.get(1)
                if (extractedVideo != null) break
            }

            if (extractedVideo != null) {
                val finalVideoUrl = extractedVideo.replace("\\/", "/").replace("\\u0026", "&")
                val isM3u8 = finalVideoUrl.contains(".m3u8")
                val hostOrigin = getBaseUrl(cleanIframe)
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Dizipal Alternatif",
                        url = finalVideoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = cleanIframe
                        this.headers = mapOf(
                            "Referer" to cleanIframe,
                            "User-Agent" to USER_AGENT,
                            "Origin" to hostOrigin
                        )
                    }
                )
                foundLinks = true

                // Altyazı çekimi
                Regex("""(?i)subtitle\s*[:=]\s*["']([^"']+)""").find(unpacked)?.groupValues?.get(1)?.let { subStr ->
                    subStr.replace("\\/", "/").split(Regex(",(?=\\[)")).forEach { track ->
                        val sep = track.indexOf(']')
                        if (track.startsWith("[") && sep > 1) {
                            val label = track.substring(1, sep)
                            val subUrl = track.substring(sep + 1).trim()
                            if (subUrl.startsWith("http")) {
                                subtitleCallback(
                                    newSubtitleFile(label, subUrl) {
                                        this.headers = mapOf("Referer" to cleanIframe)
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // Hiçbir şey bulunamazsa yerleşik Extractor'a devret
                if (loadExtractor(cleanIframe, subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(url)
    }

    // ESKİ YAPIDA KULLANILAN JS DECODER FONKSİYONU
    private fun unpackJs(packed: String): String? {
        val pattern = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(packed) ?: return null

        return runCatching {
            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: 36
            val count = match.groupValues[3].toIntOrNull() ?: 0
            val dict = match.groupValues[4].split("|")

            val wordMap = mutableMapOf<String, String>()
            for (i in 0 until count) {
                val key = i.toString(radix)
                val value = dict.getOrNull(i).takeIf { !it.isNullOrEmpty() } ?: key
                wordMap[key] = value
            }

            Regex("""\b\w+\b""").replace(payload) { m ->
                wordMap[m.value] ?: m.value
            }
        }.getOrNull()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrl(linkElem.attr("href"))
        val title = this.selectFirst(".title, h2, h3, .name")?.text()?.trim()
            ?.removeSuffix(" izle") ?: linkElem.text().trim()
        if (title.isEmpty()) return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        val isSeries = href.contains("/dizi/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }
}