package com.dizipal

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

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
        val searchUrl = "$mainUrl/arama?q=${java.net.URLEncoder.encode(query, "UTF-8")}" 
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

    // 4. VİDEO KAYNAKLARI
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframes = mutableListOf<String>()

        document.selectFirst("#videoContainer[data-cfg]")?.attr("data-cfg")?.let { encoded ->
            runCatching {
                val config = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
                config.optString("v").takeIf { it.isNotEmpty() }?.let { iframes.add(it) }
            }
        }

        document.select("iframe").forEach { iframe: Element ->
            val src = if (iframe.attr("src").isNotEmpty()) iframe.attr("src") else iframe.attr("data-src")
            if (src.isNotEmpty()) {
                iframes.add(fixUrl(src))
            }
        }

        document.select(".sources-list a, .player-options a, [data-frame]").forEach { btn: Element ->
            val frameSrc = if (btn.attr("data-frame").isNotEmpty()) btn.attr("data-frame") else btn.attr("href")
            if (frameSrc.isNotEmpty() && !frameSrc.startsWith("#")) {
                iframes.add(fixUrl(frameSrc))
            }
        }

        for (iframeUrl in iframes.distinct()) {
            if (iframeUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "Dizipal HLS",
                        iframeUrl,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                    }
                )
            } else {
                val embedHtml = runCatching { app.get(iframeUrl, referer = mainUrl).text }.getOrNull()
                val m3u8 = embedHtml?.let {
                    Regex("(?:M3U8|file)\\s*[:=]\\s*[\\\"']([^\\\"']+\\.m3u8[^\\\"']*)", RegexOption.IGNORE_CASE)
                        .find(it)?.groupValues?.get(1)
                }
                if (m3u8 != null) {
                    val normalizedM3u8 = m3u8
                        .replace("\\\\/", "/")
                        .replace("\\u0026", "&")
                    callback.invoke(
                        newExtractorLink(name, "Dizipal", normalizedM3u8, ExtractorLinkType.M3U8) {
                            this.referer = iframeUrl
                        }
                    )
                    val subtitleConfig = Regex("subtitle\\s*[:=]\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
                        .find(embedHtml)?.groupValues?.get(1)
                        ?.replace("\\\\/", "/")
                    subtitleConfig?.split(Regex(",(?=\\[)"))?.let { tracks ->
                        for (track in tracks) {
                            val separator = track.indexOf(']')
                            if (track.startsWith("[") && separator > 1) {
                                val label = track.substring(1, separator)
                                val subtitleUrl = track.substring(separator + 1).trim()
                                    .replace("\\\\/", "/")
                                    .replace("\\u0026", "&")
                                if (subtitleUrl.startsWith("http")) {
                                    subtitleCallback(
                                        newSubtitleFile(label, subtitleUrl) {
                                            headers = mapOf(
                                                "Referer" to iframeUrl,
                                                "User-Agent" to USER_AGENT
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    loadExtractor(iframeUrl, subtitleCallback, callback)
                }
            }
        }

        return iframes.isNotEmpty()
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
