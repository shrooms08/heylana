package xyz.heylana.app.screen

/**
 * The two warnings Heylana gives without being asked.
 *
 * **A screen asking for a recovery phrase.** No real Solana app ever asks for one, so a
 * screen that does is worth saying out loud whatever app it is in — a browser, a chat, a
 * game. The check is deliberately narrow: the words for a phrase *and* something that
 * looks like being asked to type it, because a wallet showing you your own phrase to write
 * down is not the same thing and gets no warning.
 *
 * **A domain that looks like a real one.** phanton.app is not phantom.app, jup1ter is not
 * jup.ag, solfIare with a capital i is not solflare. The comparison is an edit distance
 * and a fixed set of letter-for-letter swaps, against the list of real Solana domains
 * below, and against the blocklist the worker hands the phone.
 *
 * **All of it runs on the phone.** The address bar, the words on screen and the domains are
 * never sent anywhere: the whole blocklist is downloaded once a day and checked here, so no
 * one — Heylana included — learns a page anyone visited. And none of it is a verdict: the
 * words say what was found and tell the user to check the address bar.
 */
object ScamWatch {

    /** Said unprompted, word for word, when a screen asks for a phrase. */
    const val SEED_PHRASE = "No real Solana app asks for your recovery phrase. If you type it in, whoever is asking can take everything."

    /** Said unprompted when a domain is on the blocklist the worker keeps. */
    const val KNOWN_PHISHING = "This site is on a public list of known crypto phishing sites. Check the address bar."

    /** Said unprompted when a domain is one letter away from a real one. */
    fun lookAlikeWords(real: String): String = "This looks like a copy of $real. Check the address bar."

    // ---------------------------------------------------------------- a phrase, asked for

    /** The words for the one secret that is never shared. */
    private val SECRET_WORDS = Regex(
        "(recovery phrase|seed phrase|secret phrase|secret recovery|mnemonic|private key|" +
            "12[- ]word|24[- ]word|twelve word|twenty[- ]four word)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Being asked to hand it over, rather than being shown it to write down. Whole words
     * only: "recovery phrase" is the thing itself, and must never read as an asking for it.
     */
    private val ASKING = Regex(
        "(?<![\\p{L}])(enter|input|type|paste|submit|confirm|verify|restore|import|recover|validate|sync|" +
            "unlock|connect your wallet by)(?![\\p{L}])",
        RegexOption.IGNORE_CASE
    )

    /**
     * Whether [text] is a screen asking for the user's phrase or private key. Needs both
     * the words and the asking, so a wallet's own "write these 12 words down" is not it.
     */
    fun asksForSecret(text: String): Boolean {
        if (!SECRET_WORDS.containsMatchIn(text)) return false
        return ASKING.containsMatchIn(text)
    }

    // ---------------------------------------------------------------- a domain that is nearly right

    /**
     * The real Solana domains a copy would be made of. Kept short and high-traffic on
     * purpose: every name here is one a look-alike is measured against, so a name that
     * does not belong would measure a real site against nothing.
     */
    val REAL_DOMAINS: List<String> = listOf(
        "phantom.app", "solflare.com", "backpack.app", "solana.com", "solanamobile.com",
        "jup.ag", "raydium.io", "orca.so", "meteora.ag", "kamino.finance",
        "marginfi.com", "drift.trade", "marinade.finance", "jito.network", "sanctum.so",
        "magiceden.io", "tensor.trade", "solscan.io", "solana.fm", "solanabeach.io",
        "helius.dev", "quicknode.com", "birdeye.so", "dexscreener.com", "pump.fun",
        "squads.so", "tiplink.io", "step.finance", "zeta.markets", "mango.markets",
        "solend.fi", "lifinity.io", "anchor-lang.com", "metaplex.com", "pyth.network",
        "wormhole.com", "jupiter-swap.io", "coingecko.com", "coinmarketcap.com", "ledger.com",
    )

    /** The host of a web address, without the scheme, the path or a leading www. */
    fun hostOf(address: String): String? {
        val trimmed = address.trim().lowercase()
        if (trimmed.isEmpty()) return null
        val withoutScheme = trimmed.substringAfter("://", trimmed)
        val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore(':')
        val bare = host.removePrefix("www.")
        if (bare.isBlank() || !bare.contains('.')) return null
        // An address bar may show a search rather than a site.
        if (bare.contains(' ')) return null
        return bare
    }

    /**
     * Letters that read as other letters: the whole trick of a look-alike domain.
     * Applied to both sides, so jup1ter and jupiter come out the same.
     */
    fun normalise(host: String): String =
        host.lowercase()
            .replace("rn", "m")
            .replace("vv", "w")
            .replace('1', 'l')
            .replace('!', 'l')
            .replace('|', 'l')
            .replace('0', 'o')
            .replace('5', 's')
            .replace('3', 'e')
            .replace('4', 'a')
            .replace('7', 't')

    /**
     * The real domain [host] is a copy of, or null. A host that *is* one of the real ones
     * is never a copy of anything, and a subdomain of a real one (help.phantom.app) is the
     * real one. One letter of difference on a short name, two on a long one.
     */
    fun lookAlike(host: String, real: List<String> = REAL_DOMAINS): String? {
        val clean = host.removePrefix("www.").lowercase()
        if (clean.isBlank()) return null
        for (domain in real) {
            if (clean == domain || clean.endsWith(".$domain")) return null
        }
        // The last two labels are what a copy imitates; a path or a subdomain is not it.
        val labels = clean.split('.')
        if (labels.size < 2) return null
        val name = labels[labels.size - 2]
        val tld = labels.last()
        for (domain in real) {
            val realLabels = domain.split('.')
            val realName = realLabels[realLabels.size - 2]
            val realTld = realLabels.last()
            // The name is the part a copy works on, so the distance it is allowed grows
            // with the name rather than with the whole domain: one letter in a short name
            // is most of it, two in a long one is still a copy.
            val allowed = if (realName.length >= 7) 2 else 1
            // Letters that only read the same — rnagiceden for magiceden — are the copy
            // itself, whatever the ending.
            if (name != realName && normalise(name) == normalise(realName)) return domain
            // The same name under another ending — phantom.io for phantom.app — is a copy.
            if (name == realName) {
                if (tld != realTld) return domain
                continue
            }
            if (tld != realTld) continue
            if (distance(name, realName, allowed) <= allowed) return domain
            if (distance(normalise(name), normalise(realName), allowed) <= allowed) return domain
        }
        return null
    }

    /**
     * Levenshtein distance, given up on once it passes [most] — the answer is only ever
     * used as "near enough to be a copy", so there is nothing to gain past that.
     */
    fun distance(a: String, b: String, most: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > most) return most + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var best = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
                best = minOf(best, current[j])
            }
            if (best > most) return most + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    // ---------------------------------------------------------------- what to say, if anything

    /** A warning and what it is about, so the same domain is not warned about twice a day. */
    data class Warning(val words: String, val about: String, val why: Why) {
        enum class Why { SECRET, BLOCKLIST, LOOK_ALIKE }
    }

    /**
     * What this screen deserves, if anything. [blocked] is the worker's blocklist as it
     * was handed over: the check happens here, so no address ever goes anywhere.
     *
     * A wallet's own screens are left alone for the phrase warning: showing you your phrase
     * to write down is what a wallet is for, and warning there would be wrong.
     */
    fun of(
        screenText: String,
        pageAddress: String?,
        packageName: String?,
        blocked: Set<String> = emptySet(),
        wallet: Boolean = false,
    ): Warning? {
        if (!wallet && asksForSecret(screenText)) {
            return Warning(SEED_PHRASE, about = pageAddress?.let(::hostOf) ?: (packageName ?: "screen"), why = Warning.Why.SECRET)
        }
        val host = pageAddress?.let(::hostOf) ?: return null
        blockedBy(host, blocked)?.let { listed ->
            return Warning(KNOWN_PHISHING, about = listed, why = Warning.Why.BLOCKLIST)
        }
        lookAlike(host)?.let { real ->
            return Warning(lookAlikeWords(real), about = host, why = Warning.Why.LOOK_ALIKE)
        }
        return null
    }

    /** A domain as the blocklist holds it: lower case, no www., nothing else. */
    fun keyOf(host: String): String = host.trim().lowercase().removePrefix("www.").removeSuffix(".")

    // ---------------------------------------------------------------- a listed domain, or under one

    /**
     * Hosts where anyone can put up a site of their own, one subdomain each. A listed
     * bad.vercel.app is a scam; vercel.app is everybody's, and is never matched.
     */
    val SHARED_PARENTS: Set<String> = setOf(
        "vercel.app", "netlify.app", "pages.dev", "github.io", "web.app", "firebaseapp.com",
        "herokuapp.com", "onrender.com", "workers.dev", "gitbook.io", "notion.site", "webflow.io",
        "framer.website", "wixsite.com", "blogspot.com",
    )

    /**
     * Endings under which names are registered, two labels deep. Every single label (com,
     * io, xyz, app…) is one too. A match stops at the registrable domain and never reaches
     * one of these.
     */
    val PUBLIC_SUFFIXES: Set<String> = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk",
        "com.au", "net.au", "org.au", "co.nz", "org.nz", "co.jp", "ne.jp", "or.jp",
        "com.br", "com.cn", "com.mx", "co.in", "co.za", "com.tr", "com.sg", "com.hk",
        "co.kr", "com.ar", "com.ng", "com.co", "com.tw", "com.my", "com.ph", "com.vn",
        "co.id", "com.pk", "com.eg", "com.sa", "com.ua", "co.il", "com.pl", "co.th",
    )

    /**
     * The listed domain [host] is, or sits under, or null. The host itself and each parent
     * are looked up in turn, down to the registrable domain: x.bad.example.com is caught by
     * a listed bad.example.com or example.com. A public suffix or a shared hosting parent is
     * never a match, whatever the list says, and a real Solana domain (or one of its
     * subdomains) is never reported as listed.
     */
    fun blockedBy(host: String, blocked: Set<String>): String? {
        if (blocked.isEmpty()) return null
        val key = keyOf(host)
        if (REAL_DOMAINS.any { key == it || key.endsWith(".$it") }) return null
        var candidate = key
        while (true) {
            if (candidate in blocked) return candidate
            val parent = candidate.substringAfter('.', "")
            if (!parent.contains('.') || parent in SHARED_PARENTS || parent in PUBLIC_SUFFIXES) return null
            candidate = parent
        }
    }
}
