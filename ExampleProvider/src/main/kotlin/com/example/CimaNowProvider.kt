package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CimaNowProvider : MainAPI() {
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "ar"
    override val hasMainPage = true

    // 1. جلب الصفحة الرئيسية وتقسيمها إلى أقسام (سلايدر، أفلام مضافة حديثاً، إلخ)
    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = mutableListOf<HomePageList>()
        
        // جلب الأقسام المختلفة مثل "أحدث الأفلام" و "أحدث المسلسلات"
        document.select("div.items").forEach { section ->
            val title = section.previousElementSibling()?.text() ?: "المقترحات"
            val homeItems = section.select("article").mapNotNull {
                it.toSearchResult()
            }
            if (homeItems.isNotEmpty()) {
                items.add(HomePageList(title, homeItems))
            }
        }
        return HomePageResponse(items)
    }

    // 2. البحث داخل الموقع
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.result-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. جلب بيانات الفيلم أو الحلقات
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("div.data h1")?.text() ?: return null
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val description = document.selectFirst("div.wp-content p")?.text()

        return if (url.contains("/series/")) {
            // جلب الحلقات للمسلسلات
            val episodes = document.select("ul.episodios li").map {
                val href = it.selectFirst("a")?.attr("href") ?: ""
                val name = it.selectFirst("div.numerando")?.text() ?: ""
                Episode(href, name)
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // للأفلام
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // 4. استخراج روابط الفيديو من السيرفرات
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // البحث عن روابط الـ Iframe داخل صفحة المشاهدة
        document.select("ul.dooplay_player_option li").forEach {
            val type = it.attr("data-type")
            val post = it.attr("data-post")
            val num = it.attr("data-nume")
            
            // طلب الرابط الفعلي للسيرفر عبر AJAX الخاص بالقالب
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to num, "type" to type)
            ).parsed<ResponseSource>()
            
            val embedUrl = response.embed_url ?: ""
            // استخدام المستخرجات الجاهزة (مثل Fembed, Mixdrop, Upstream)
            loadExtractor(embedUrl, data, subtitleCallback, callback)
        }
        return true
    }

    data class ResponseSource(val embed_url: String?)

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.title a, h3 a")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        return MovieSearchResponse(title, href, this@CimaNowProvider.name, TvType.Movie, posterUrl)
    }
}
