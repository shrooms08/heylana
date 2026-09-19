package xyz.heylana.app.brain

/**
 * Where an answer came from, as a chip under it: a short title and the page it opens.
 * [title] is at most [Sources.MAX_TITLE] characters: "Solana Cookbook: How to Add…".
 */
data class Source(val title: String, val url: String)

/**
 * Source chips. An answer that used the knowledge base (the worker hands back what the
 * model cited), the error table, a lesson's topic or the page the user is on gets up to
 * [MAX_CHIPS] chips; tapping one opens its page in the browser. The link is on the chip,
 * never in the words: nothing spoken ever reads a URL aloud ([spoken]).
 */
object Sources {

    const val MAX_TITLE = 40
    const val MAX_CHIPS = 2

    /** How a knowledge-base source is named on a chip. */
    private val SHORT = mapOf(
        "solana.com docs" to "Solana docs",
        "Solana Cookbook" to "Solana Cookbook",
        "Anchor docs" to "Anchor docs",
        "Solana Mobile docs" to "Solana Mobile",
        "Solana Stack Exchange" to "Stack Exchange",
        "Agave release notes" to "Agave",
        "Agave changelog" to "Agave",
        "Anchor changelog" to "Anchor",
        "X" to "X",
    )

    /** "Solana Cookbook: How to Add Priority Fees…", cut at a word to fit a chip. */
    fun chipTitle(source: String, title: String): String {
        val lead = SHORT[source] ?: source
        val whole = if (title.isBlank()) lead else if (lead.isBlank()) title else "$lead: $title"
        return fit(whole.trim())
    }

    fun fit(text: String): String {
        if (text.length <= MAX_TITLE) return text
        val cut = text.take(MAX_TITLE - 1)
        val space = cut.lastIndexOf(' ')
        return (if (space > MAX_TITLE / 2) cut.take(space) else cut).trimEnd(',', ':', ';', '-', ' ') + "…"
    }

    /** Only pages worth opening: https, and never more than [MAX_CHIPS], each page once. */
    fun chips(sources: List<Source>): List<Source> =
        sources.filter { it.url.startsWith("https://") && it.title.isNotBlank() }.distinctBy { it.url }.take(MAX_CHIPS)

    /** Pages Heylana links to itself (the error table's), by address: what their chip says. */
    private val KNOWN = mapOf(
        "https://www.anchor-lang.com/docs/features/errors" to "Anchor docs: errors",
        "https://www.anchor-lang.com/docs/references/account-constraints" to "Anchor docs: account constraints",
        "https://solana.com/docs/core/transactions" to "Solana docs: transactions",
        "https://solana.com/docs/core/fees" to "Solana docs: fees",
        "https://solana.com/docs/core/fees/compute-budget" to "Solana docs: compute budget",
        "https://solana.com/docs/core/cpi" to "Solana docs: CPI",
        "https://solana.com/docs/core/accounts" to "Solana docs: accounts",
        "https://solana.com/docs/core/programs" to "Solana docs: programs",
        "https://solana.com/docs/tokens" to "Solana docs: tokens",
        "https://solana-mobile.github.io/mobile-wallet-adapter/spec/spec.html" to "Mobile Wallet Adapter spec",
        "https://docs.solanamobile.com/developers/seed-vault" to "Solana Mobile: Seed Vault",
    )

    /** The chip for one of Heylana's own links, or one named after its site. */
    fun forLink(url: String): Source = Source(KNOWN[url] ?: fit(siteOf(url)), url)

    /** What a spoken line calls the page behind a chip: "the Anchor docs", "the Solana docs". */
    fun spokenName(url: String): String = when {
        "anchor-lang.com" in url -> "the Anchor docs"
        "solanamobile" in url || "solana-mobile" in url -> "the Solana Mobile docs"
        "stackexchange.com" in url -> "Solana Stack Exchange"
        "solana.com" in url -> "the Solana docs"
        else -> "the linked page"
    }

    /** The page a browser is showing, as a chip: "Page: solana.stackexchange.com/questions/…". */
    fun forPage(address: String): Source? {
        val bare = address.trim().removePrefix("https://").removePrefix("http://")
        if (bare.isBlank() || ' ' in bare || '.' !in bare.substringBefore('/')) return null
        return Source(fit("Page: $bare"), "https://$bare")
    }

    /** Hosts whose pages a stray link in an answer may still become a chip for. */
    private val DOC_HOSTS = setOf(
        "solana.com", "www.anchor-lang.com", "anchor-lang.com", "docs.solanamobile.com", "solana-mobile.github.io",
        "solana.stackexchange.com", "docs.anza.xyz", "solanacookbook.com", "spl.solana.com",
    )

    /**
     * Links the model wrote into its words anyway (it is told not to): the ones on the docs
     * sites above become chips, the rest are only dropped from the words.
     */
    fun inText(text: String): List<Source> =
        URL.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }
            .filter { it.startsWith("https://") && siteOf(it) in DOC_HOSTS }
            .map { forLink(it) }.toList()

    private fun siteOf(url: String): String = url.removePrefix("https://").substringBefore('/')

    private val URL = Regex("https?://\\S+|\\bwww\\.\\S+")

    /** [text] as it may be spoken or shown in the strip: no URL in it, ever. */
    fun spoken(text: String): String =
        text.replace(Regex("\\s*(More|Link|See):\\s*" + URL.pattern + "\\.?"), "").replace(URL, "").replace(Regex("\\s{2,}"), " ").trim()
}
