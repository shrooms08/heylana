package xyz.heylana.app.screen

/**
 * Whether the page in front carries on past the bottom of the screen, and what Heylana says
 * about it. A read only ever has what is on screen; when the page clearly continues (a
 * scrolling area that can still scroll down, or elements the tree places below the screen),
 * an answer about the page is from the visible part only and says so in one line. It never
 * claims to have read the whole page.
 */
object PageExtent {

    /** Added, by the phone, to an answer about a page that continues below. */
    const val LINE = "That's what's on screen; there's more below."

    /**
     * A scrolling area that can still go down counts only if it is a real part of the page —
     * at least [MIN_SHARE] of the window's height — so a small carousel or a chip row does not.
     */
    const val MIN_SHARE = 0.4f

    fun scrollsDown(scrollable: Boolean, canScrollDown: Boolean, height: Int, windowHeight: Int): Boolean =
        scrollable && canScrollDown && windowHeight > 0 && height >= windowHeight * MIN_SHARE

    /** An element the tree has, but below the bottom of the window: content the user hasn't reached. */
    fun hiddenBelow(visible: Boolean, top: Int, windowBottom: Int): Boolean = !visible && windowBottom > 0 && top >= windowBottom

    private val ABOUT_PAGE = Regex(
        "\\b(page|article|post|thread|answer|answers|question|comments?|docs?|doc|guide|tutorial|summar\\w*|tl;?dr|" +
            "what does (it|this|that) say|what is this about|what's this about|read|explain this|this says)\\b",
        RegexOption.IGNORE_CASE
    )

    /** A question about the page itself: anything asked over a browser, or words about reading one elsewhere. */
    fun aboutThePage(question: String, packageName: String?): Boolean =
        packageName in xyz.heylana.app.lessons.LessonWords.BROWSERS || ABOUT_PAGE.containsMatchIn(question)

    /**
     * The answer as said: [LINE] after it when the page continues and the answer came from
     * what is visible; unchanged when the model said the answer is not on screen ([unseen]),
     * or when it already says so.
     */
    fun withLine(text: String, moreBelow: Boolean, unseen: Boolean): String = when {
        !moreBelow || unseen || text.isBlank() -> text
        text.contains("more below", ignoreCase = true) -> text
        else -> "${text.trimEnd()} $LINE"
    }
}
