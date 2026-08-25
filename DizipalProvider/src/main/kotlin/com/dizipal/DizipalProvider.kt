package com.dizipal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class DizipalProvider : MainAPI() {
    override var mainUrl = "https://dizipal2301.com"
    override var name = "Dizipal"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    // 1. ANA SAYFA
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homeLists = mutableListOf<HomePageList>()

        val sections = document.select("div.section, div.module, div.content-wrapper")
        if (sections.isNotEmpty()) {
            for (section: Element in sections) {
                val title = section.selectFirst("h2, h3, .section-title")?.text()?.trim() ?: "İçerikler"
                val items = section.select("article, div.poster, div.movie-card, .item").mapNotNull { it.toSearchResult() }
                if (items.isNotEmpty()) {
                    homeLists.add(HomePageList(title, items))
                }
            }
        } else {
            val items = document.select("article, div.poster, div.movie-card, .item").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homeLists.add(HomePageList("Öne Çıkanlar", items))
            }
        }

        return newHomePageResponse(homeLists)
    }

    // 2. ARAMA
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document

        return document.select("article, div.poster, div.search-result, .item").mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. DETAY VE BÖLÜM LİSTESİ
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .entry-title, .title")?.text()?.trim() ?: "Bilinmeyen Başlık"
        val poster = fixUrlNull(document.selectFirst(".poster img, .cover img, img.entry-thumb")?.attr("src") ?: document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst(".description, .overview, .entry-content p, #sinopsis")?.text()?.trim()
        val year = document.selectFirst(".release-year, .year, .date")?.text()?.trim()?.toIntOrNull()

        val episodeElements = document.select(".episodes a, .episode-item, div.episodes-list a, li.episode")
        val isSeries = episodeElements.isNotEmpty() || url.contains("/dizi/")

        return if (isSeries) {
            val episodes = episodeElements.mapIndexed { index: Int, element: Element ->
                val epUrl = fixUrl(element.attr("href"))
                val epName = element.selectFirst(".name, .title")?.text()?.trim() ?: element.text().trim()
                
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
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
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
                loadExtractor(iframeUrl, subtitleCallback, callback)
            }
        }

        return iframes.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrl(linkElem.attr("href"))
        val title = this.selectFirst(".title, h2, h3, .name")?.text()?.trim() ?: linkElem.text().trim()
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
