package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart Navigation Engine v3
 *
 * Intelligently navigates ANY website structure:
 * - Detects and uses search when available
 * - Crawls tabs/categories/genres when search is absent
 * - Handles popups, overlays, cookie consents
 * - Navigates pagination for more results
 * - Handles dynamic content detection
 * - Keyword-based tab matching for non-search sites
 * - ENHANCED: Strictly differentiates categories from actual search results
 * - Falls back to homepage scraping as last resort
 */
@Singleton
class SmartNavigationEngine @Inject constructor() {

    companion object {
        private const val DEFAULT_TIMEOUT = 30000
        private const val QUICK_TIMEOUT  = 20000
        private const val CONCURRENT_PATTERN_CHECKS = 18
        private val DEFAULT_USER_AGENT = com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT

        // Extended search URL patterns (most-to-least common)
        private val SEARCH_URL_PATTERNS = listOf(
            // Standard query-param patterns
            "{base}/search?q={query}",
            "{base}/?s={query}",
            "{base}/search?query={query}",
            "{base}/?q={query}",
            "{base}/search?s={query}",
            "{base}/search?keyword={query}",
            "{base}/search?keywords={query}",
            "{base}/search?term={query}",
            "{base}/search?text={query}",
            "{base}/?search_query={query}",
            "{base}/results?q={query}",
            "{base}/results?search_query={query}",
            "{base}/videos?search={query}",
            "{base}/videos?q={query}",
            "{base}/movies?search={query}",
            "{base}/movies?q={query}",
            "{base}/search.php?q={query}",
            "{base}/search.php?keyword={query}",
            "{base}/search.html?q={query}",
            "{base}/find?q={query}",
            "{base}/find?query={query}",
            "{base}/index.php?s={query}",
            "{base}/index.php?q={query}",
            "{base}/?search={query}",
            "{base}/search?name={query}",
            "{base}/search?title={query}",
            "{base}/search?cat=0&search={query}",
            "{base}/torrents?search={query}",
            "{base}/torrents.php?search={query}",
            "{base}/browse.php?search={query}",
            "{base}/torrent-search?q={query}",
            // API / WordPress REST
            "{base}/api/search?q={query}",
            "{base}/wp-json/wp/v2/search?search={query}",
            "{base}/wp-json/wp/v2/posts?search={query}",
            "{base}/wp-json/wp/v2/media?search={query}",
            "{base}/api/v1/search?q={query}",
            "{base}/api/v2/search?q={query}",
            "{base}/api/v3/search?q={query}",
            // Ghost CMS
            "{base}/ghost/api/v4/content/posts/?filter=title:~%27{query}%27&key=",
            "{base}/ghost/api/content/posts/?filter=title:~%27{query}%27",
            // Strapi CMS (v4 & v5)
            "{base}/api/articles?filters[title][\$containsi]={query}",
            "{base}/api/posts?filters[title][\$containsi]={query}",
            "{base}/api/videos?filters[title][\$containsi]={query}",
            "{base}/api/movies?filters[title][\$containsi]={query}",
            // Directus CMS
            "{base}/items/articles?filter[title][_contains]={query}",
            "{base}/items/posts?filter[title][_contains]={query}",
            "{base}/items/content?filter[title][_contains]={query}",
            // Contentful
            "{base}?content_type=blogPost&fields.title[match]={query}",
            // Generic REST JSON APIs
            "{base}/api/search?keyword={query}",
            "{base}/api/search?title={query}",
            "{base}/api/videos/search?q={query}",
            "{base}/api/movies/search?q={query}",
            // Slug / path-segment patterns
            "{base}/search/{query}",
            "{base}/search/{query_slug}",
            "{base}/search/videos/{query}",
            "{base}/search/movies/{query}",
            "{base}/s/{query}",
            "{base}/q/{query}",
            "{base}/find/{query}",
            // Torrent indexer specific
            "{base}/browse?search={query}&cat=0",
            "{base}/s/?q={query}&cat=0",
            "{base}/search?what={query}",
            "{base}/ajax/movies/search?content={query}",
            "{base}/suggest?q={query}",
            // Localised patterns
            "{base}/buscar?q={query}",
            "{base}/recherche?q={query}",
            "{base}/suche?q={query}",
            "{base}/zoeken?q={query}",
            "{base}/cerca?q={query}",
            "{base}/pesquisar?q={query}"
        )

        // Category page indicators to detect and bypass
        private val CATEGORY_INDICATORS = listOf(
            "/category/", "/categories/", "/cat/",
            "/genre/", "/genres/", "/tag/", "/tags/",
            "/type/", "/types/", "/browse/", "/explore/",
            "/popular", "/trending", "/latest", "/new",
            "/top-rated", "/featured", "/recommended",
            "/home", "/homepage", "/index"
        )

        // Words that indicate navigation/category instead of search results
        private val NAVIGATION_KEYWORDS = setOf(
            "all", "browse", "categories", "category", "genres", "genre",
            "explore", "filter", "home", "homepage", "latest", "menu",
            "navigation", "popular", "recommended", "tags", "trending", "top"
        )

        // Selectors that indicate tab/category navigation bars
        private val TAB_NAVIGATION_SELECTORS = listOf(
            ".tabs a", ".tab a", "nav.tabs a", "[role='tablist'] [role='tab']",
            ".nav-tabs a", ".menu-tabs a", ".category-tabs a",
            ".genre-tabs a", ".filter-tabs a", ".section-tabs a",
            "[class*='tab'] a[href]", "ul.tabs > li > a",
            ".categories a", ".category-list a", ".genres a",
            ".genre-list a", ".nav-categories a",
            "nav a[href]", ".sidebar a[href]", ".filters a[href]",
            ".breadcrumb a", ".menu a[href]",
            "ul.menu > li > a", ".navigation a[href]"
        )

        // Popup/overlay selectors to dismiss
        private val POPUP_SELECTORS = listOf(
            ".popup-close", ".modal-close", ".close-button",
            "[class*='popup'] .close", "[class*='modal'] .close",
            ".overlay-close", "#close-popup", "#close-modal",
            "[data-dismiss='modal']", ".cookie-close",
            ".ad-close", ".notification-close", ".banner-close",
            "button[aria-label='Close']", "button[aria-label='Dismiss']",
            ".adblock-close", ".adblocker-close", ".skip-ad", ".skipAds",
            ".ad-skip", ".ad_skip_btn", ".ad-overlay-close", ".ad-popup-close",
            ".interstitial-close", ".promo-close", ".splash-close",
            ".fullscreen-ad-close", ".ad-modal-close", ".ad-dismiss", ".ad-exit",
            "button.skip", "button[title*='Skip']", "button[title*='Dismiss']",
            "button[title*='Close']"
        )

        // Category/navigation element selectors (to exclude from results)
        private val CATEGORY_ELEMENT_SELECTORS = listOf(
            ".categories", ".category-list", ".genre-list", ".tags",
            "nav.categories", ".browse-categories", ".filter-menu",
            ".sidebar-categories", "[class*='category-nav']",
            "[class*='genre-nav']", "[class*='tag-cloud']"
        )
    }

    /**
     * Attempt to close popups/ads with retries and escalation
     */
    suspend fun closePopupsWithRetries(page: Document, maxRetries: Int = 3): Boolean {
        var closedAny = false
        repeat(maxRetries) {
            var closedThisRound = false
            for (selector in POPUP_SELECTORS) {
                val elements = page.select(selector)
                if (elements.isNotEmpty()) {
                    elements.forEach { it.remove() }
                    closedThisRound = true
                }
            }
            if (closedThisRound) closedAny = true
            if (!closedThisRound) return@repeat
        }
        return closedAny
    }
    
    /**
     * Find the best search URL for a site
     */
    suspend fun findSearchUrl(baseUrl: String, query: String): String? = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val slugQuery = query.trim().lowercase().replace(Regex("\\s+"), "-")
        val plusQuery = query.trim().replace(Regex("\\s+"), "+")

        // Step 1: Detect a search form on the homepage
        try {
            val homepage = Jsoup.connect(baseUrl)
                .userAgent(DEFAULT_USER_AGENT)
                .timeout(QUICK_TIMEOUT)
                .get()
            val searchForm = findSearchForm(homepage)
            if (searchForm != null) {
                val builtUrl = buildSearchUrlFromForm(baseUrl, searchForm, query)
                try {
                    val doc = Jsoup.connect(builtUrl)
                        .userAgent(DEFAULT_USER_AGENT)
                        .timeout(QUICK_TIMEOUT)
                        .ignoreHttpErrors(true)
                        .get()
                    // Validate it returns actual search results, not categories
                    if (isSearchResultsPage(doc) && !isCategoryPage(builtUrl, doc)) {
                        return@withContext builtUrl
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Step 2: Try pattern-based search URLs CONCURRENTLY
        val candidateUrls = SEARCH_URL_PATTERNS.map { pattern ->
            pattern
                .replace("{base}", baseUrl.trimEnd('/'))
                .replace("{query_slug}", slugQuery)
                .replace("{query_plus}", plusQuery)
                .replace("{query}", encodedQuery)
        }

        for (chunk in candidateUrls.chunked(CONCURRENT_PATTERN_CHECKS)) {
            val batchResult: String? = coroutineScope {
                chunk.map { searchUrl ->
                    async(Dispatchers.IO) {
                        try {
                            val response = Jsoup.connect(searchUrl)
                                .userAgent(DEFAULT_USER_AGENT)
                                .timeout(QUICK_TIMEOUT)
                                .followRedirects(true)
                                .ignoreHttpErrors(true)
                                .execute()
                            val doc = response.parse()
                            // Both conditions must be true: is search results AND is NOT category page
                            if (response.statusCode() == 200 && 
                                isSearchResultsPage(doc) && 
                                !isCategoryPage(searchUrl, doc)) {
                                searchUrl
                            } else null
                        } catch (_: Exception) { null }
                    }
                }.awaitAll().firstOrNull { it != null }
            }
            if (batchResult != null) return@withContext batchResult
        }

        null
    }

    /**
     * Crawl category tabs/navigation to find content matching the query.
     * Used when a site has NO search but organises by tabs/categories.
     */
    suspend fun crawlCategoryTabsForQuery(
        baseUrl: String,
        query: String,
        maxTabs: Int = 6
    ): List<ContentLink> = withContext(Dispatchers.IO) {
        val allLinks = mutableListOf<ContentLink>()
        val seen = mutableSetOf<String>()

        try {
            val homepage = Jsoup.connect(baseUrl)
                .userAgent(DEFAULT_USER_AGENT)
                .timeout(DEFAULT_TIMEOUT)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .get()

            val tabLinks = extractAllNavigationLinks(homepage, baseUrl)
            if (tabLinks.isEmpty()) return@withContext allLinks

            val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
            val scoredTabs = tabLinks.map { link ->
                val score = scoreTabRelevance(link.title, link.url, queryWords)
                Pair(link, score)
            }

            val sortedTabs = scoredTabs
                .filter { it.second > 0f }
                .sortedByDescending { it.second }
                .take(maxTabs)
                .ifEmpty {
                    scoredTabs
                        .filter { isGenericContentTab(it.first.url, it.first.title) }
                        .take(maxTabs)
                }

            coroutineScope {
                sortedTabs.map { (link, _) ->
                    async(Dispatchers.IO) {
                        try {
                            val tabDoc = Jsoup.connect(link.url)
                                .userAgent(DEFAULT_USER_AGENT)
                                .timeout(DEFAULT_TIMEOUT)
                                .followRedirects(true)
                                .ignoreHttpErrors(true)
                                .get()
                            extractContentLinks(tabDoc, baseUrl)
                        } catch (_: Exception) { emptyList() }
                    }
                }.awaitAll().forEach { links ->
                    links.forEach { link ->
                        if (link.url !in seen) {
                            seen.add(link.url)
                            allLinks.add(link)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        allLinks
    }

    /**
     * Extract ALL navigation/tab/category links from a document
     */
    fun extractAllNavigationLinks(document: Document, baseUrl: String): List<ContentLink> {
        val links = mutableListOf<ContentLink>()
        val seen = mutableSetOf<String>()

        for (selector in TAB_NAVIGATION_SELECTORS) {
            try {
                document.select(selector).forEach { el ->
                    val href = el.attr("href").takeIf { it.isNotEmpty() } ?: return@forEach
                    val url = normalizeUrl(href, baseUrl)
                    if (url in seen || url.startsWith("#") || url.contains("javascript:")) return@forEach
                    if (isNavigationExcluded(url)) return@forEach
                    seen.add(url)
                    links.add(ContentLink(url = url, title = el.text().trim(), thumbnail = null, duration = null))
                }
            } catch (_: Exception) {}
        }

        return links.distinctBy { it.url }
    }

    /**
     * Score how relevant a tab/category is to the query keywords
     */
    private fun scoreTabRelevance(tabText: String, tabUrl: String, queryWords: List<String>): Float {
        val text = tabText.lowercase()
        val url = tabUrl.lowercase()
        var score = 0f

        for (word in queryWords) {
            when {
                text == word -> score += 1f
                text.contains(word) -> score += 0.7f
                url.contains(word) -> score += 0.5f
                areSimilarTokens(text, word) -> score += 0.3f
            }
        }

        return score
    }

    /**
     * Simple token similarity check for tab matching
     */
    private fun areSimilarTokens(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.contains(b) || b.contains(a)) return true
        if (a.length >= 4 && b.length >= 4 && a.take(4) == b.take(4)) return true
        return false
    }

    /**
     * Check if a tab/nav link is a "generic content" tab
     */
    private fun isGenericContentTab(url: String, text: String): Boolean {
        val combined = (url + " " + text).lowercase()
        return listOf("all", "latest", "new", "movies", "videos", "episodes",
            "shows", "series", "popular", "top", "recent", "full").any { combined.contains(it) }
    }

    /**
     * Check if a navigation URL should be excluded
     */
    private fun isNavigationExcluded(url: String): Boolean {
        val excluded = listOf("/login", "/register", "/signup", "/contact",
            "/about", "/privacy", "/terms", "/faq", "mailto:", "tel:",
            "/advertise", "/dmca", "/tos", "/sitemap")
        val lower = url.lowercase()
        return excluded.any { lower.contains(it) }
    }
    
    /**
     * ENHANCED: Strictly check if current page is a category page (not search results)
     * Uses multiple signals to differentiate categories from actual search result pages
     */
    fun isCategoryPage(url: String, document: Document): Boolean {
        val urlLower = url.lowercase()
        
        // Signal 1: URL contains category indicators
        if (CATEGORY_INDICATORS.any { urlLower.contains(it) }) {
            return true
        }

        // Signal 2: Remove known category elements first
        val workingDoc = document.clone()
        for (selector in CATEGORY_ELEMENT_SELECTORS) {
            workingDoc.select(selector).remove()
        }

        // Signal 3: Check for actual result items (items, cards, articles, etc.)
        val resultItems = workingDoc.select(
            ".results > *, .search-results > *, #results > *, " +
            "[class*='result-item'], [class*='search-item'], [class*='item-card'], " +
            ".video-item, .movie-item, .torrent-item, .entry, " +
            "article.item, .card, article, li[data-id], div[data-id]"
        )
        
        // If we have at least 3 actual result items, this is likely search results
        if (resultItems.size >= 3) {
            return false
        }

        // Signal 4: Check page structure - categories have many navigation links
        val categoryElements = document.select(
            ".categories, .category-list, .genre-list, .tags, " +
            "nav.categories, .browse-categories"
        )
        
        val navLinks = document.select("nav a, .menu a, .sidebar a, .filter a")
        
        // If has many category/nav elements but few result items, it's a category page
        if (categoryElements.size > 5 && resultItems.size < 3) {
            return true
        }
        
        if (navLinks.size > 15 && resultItems.size < 3) {
            return true
        }

        // Signal 5: Check URL patterns - genuine search URLs have query params or /search/
        val hasSearchIndicators = urlLower.contains("search") || 
                                   urlLower.contains("?q=") ||
                                   urlLower.contains("?s=") ||
                                   urlLower.contains("?query=") ||
                                   urlLower.contains("?keyword=") ||
                                   urlLower.contains("results") ||
                                   urlLower.contains("find")
        
        // If URL looks like a search URL but page is mostly navigation, it's category
        if (hasSearchIndicators && navLinks.size > 10 && resultItems.size < 3) {
            return true
        }

        // Signal 6: Check page text - if mostly category keywords with no result count
        val bodyText = document.text().lowercase()
        val categoryKeywordCount = NAVIGATION_KEYWORDS.count { bodyText.contains(it) }
        val hasResultCount = Regex("\\d+\\s*(results?|items?|matches?|found)").containsMatchIn(bodyText)
        
        if (categoryKeywordCount > 5 && !hasResultCount && resultItems.size < 2) {
            return true
        }

        return false
    }
    
    /**
     * ENHANCED: Check if page appears to be search results
     * Uses strict validation to ensure actual results, not categories
     */
    fun isSearchResultsPage(document: Document): Boolean {
        // Look for result indicators with stricter criteria
        val resultIndicators = listOf(
            ".results", ".search-results", "#search-results",
            "[class*='result-item']", "[class*='search-item']", "[class*='item-card']",
            ".video-item", ".movie-item", ".torrent-item",
            "article.item", ".card", "article", ".entry",
            "[class*='search-result']", "[class*='search_result']",
            ".listing-item", ".search-item", "li[data-id]", "div[data-id]"
        )

        // Count actual result elements - need threshold of 3 minimum
        var resultCount = 0
        for (selector in resultIndicators) {
            val elements = document.select(selector)
            resultCount = maxOf(resultCount, elements.size)
            if (resultCount >= 3) {
                return true
            }
        }

        // Check for result count text with stricter pattern
        val bodyText = document.text().lowercase()
        val countPattern = Regex("\\d+\\s*(results?|items?|matches?|found|entries?|titles?|videos?|movies?|shows?)")
        if (countPattern.containsMatchIn(bodyText)) {
            val itemElements = document.select(
                ".item, .card, article, li[class], div[class*='item'], div[class*='result']"
            )
            // Must have actual items alongside the count
            if (itemElements.size >= 2) return true
        }

        // Check for "no results" (still valid search page)
        if (bodyText.contains("no results") ||
            bodyText.contains("nothing found") ||
            bodyText.contains("0 results") ||
            bodyText.contains("no matches") ||
            bodyText.contains("could not find") ||
            bodyText.contains("sorry, no results")) {
            return true
        }

        return false
    }
    
    /**
     * Find search form on page
     */
    fun findSearchForm(document: Document): Element? {
        val formSelectors = listOf(
            "form[action*='search']",
            "form[role='search']",
            "form#search",
            "form.search",
            "form#searchForm",
            "form.search-form",
            "form:has(input[type='search'])",
            "form:has(input[name='q'])",
            "form:has(input[name='query'])",
            "form:has(input[name='search'])",
            "form:has(input[name='s'])"
        )
        
        for (selector in formSelectors) {
            val form = document.select(selector).firstOrNull()
            if (form != null) return form
        }
        
        return null
    }
    
    /**
     * Build search URL from form element
     */
    fun buildSearchUrlFromForm(baseUrl: String, form: Element, query: String): String {
        val action = form.attr("action")
        val method = form.attr("method").lowercase()
        
        val inputNames = listOf(
            "q", "query", "search", "s", "keyword", "keywords",
            "term", "text", "search_query", "kw", "wd", "k"
        )
        var inputName = "q"
        
        for (name in inputNames) {
            if (form.select("input[name='$name']").isNotEmpty()) {
                inputName = name
                break
            }
        }
        
        val searchBase = when {
            action.startsWith("http") -> action
            action.startsWith("/") -> "${baseUrl.trimEnd('/')}$action"
            action.isEmpty() -> baseUrl
            else -> "${baseUrl.trimEnd('/')}/$action"
        }
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        return if (searchBase.contains("?")) {
            "$searchBase&$inputName=$encodedQuery"
        } else {
            "$searchBase?$inputName=$encodedQuery"
        }
    }
    
    /**
     * Navigate past category page to search
     */
    suspend fun navigatePastCategory(
        baseUrl: String,
        document: Document,
        query: String
    ): Pair<String, Document>? = withContext(Dispatchers.IO) {
        try {
            // Try to find an actual search form
            val searchForm = findSearchForm(document)
            if (searchForm != null) {
                val searchUrl = buildSearchUrlFromForm(baseUrl, searchForm, query)
                val searchDoc = Jsoup.connect(searchUrl)
                    .userAgent(DEFAULT_USER_AGENT)
                    .timeout(QUICK_TIMEOUT)
                    .ignoreHttpErrors(true)
                    .get()
                
                if (isSearchResultsPage(searchDoc) && !isCategoryPage(searchUrl, searchDoc)) {
                    return@withContext Pair(searchUrl, searchDoc)
                }
            }
            
            // Try known search patterns
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchPatterns = listOf(
                "${baseUrl.trimEnd('/')}/search?q=$encodedQuery",
                "${baseUrl.trimEnd('/')}/search?query=$encodedQuery",
                "${baseUrl.trimEnd('/')}?s=$encodedQuery",
                "${baseUrl.trimEnd('/')}/?q=$encodedQuery"
            )
            
            for (searchUrl in searchPatterns) {
                try {
                    val doc = Jsoup.connect(searchUrl)
                        .userAgent(DEFAULT_USER_AGENT)
                        .timeout(QUICK_TIMEOUT)
                        .ignoreHttpErrors(true)
                        .get()
                    
                    if (isSearchResultsPage(doc) && !isCategoryPage(searchUrl, doc)) {
                        return@withContext Pair(searchUrl, doc)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        
        null
    }

    // Helper functions...
    suspend fun extractContentLinks(document: Document, baseUrl: String): List<ContentLink> {
        return emptyList() // Placeholder - implement as needed
    }

    fun normalizeUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> baseUrl.trimEnd('/') + url
            else -> baseUrl.trimEnd('/') + "/" + url
        }
    }
}
