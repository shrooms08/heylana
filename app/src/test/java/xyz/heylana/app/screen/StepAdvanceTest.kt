package xyz.heylana.app.screen

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.heylana.app.screen.StepAdvance.Decision

/** The step rule fed made-up event streams, the way the Seeker's session went wrong. */
class StepAdvanceTest {

    private val app = "com.solanamobile.wallet"
    private val own = StepAdvance.OWN_PACKAGE
    private val swap = "Button|swap|Swap"
    private val screen = (1..20).map { "node$it" }.toSet() + swap

    private fun step(landedAt: Long = 1_000L) = StepAdvance(swap, app, screen, landedAt)

    /** [screen] with [changed] of its nodes swapped for new ones. */
    private fun changed(changed: Int) = screen.drop(changed).toSet() + (1..changed).map { "new$it" }

    private val click = Decision.Advance(StepAdvance.Reason.CLICK)
    private val change = Decision.Advance(StepAdvance.Reason.CONTENT_CHANGED)

    @Test
    fun `the seeker's session - our windows and the app settling around them never advance`() {
        val step = step(landedAt = 1_000L)
        // Our disc, ring and strip appearing.
        assertEquals(Decision.Ignore, step.onContentChange(1_100L, own, app, screen))
        // The wallet re-laying out around them, a few hundred ms after landing, even a lot.
        assertEquals(Decision.Ignore, step.onContentChange(1_300L, app, app, changed(10)))
        // An event with no package at all.
        assertEquals(Decision.Ignore, step.onContentChange(3_000L, null, app, changed(12)))
        assertEquals(Decision.Ignore, step.onClick(3_000L, swap, null))
        // Later, the same settled screen reported again is not a change: the settle became the baseline.
        assertEquals(Decision.Ignore, step.onContentChange(4_000L, app, app, changed(10)))
    }

    @Test
    fun `a tap on the pointed element advances, after the quiet time`() {
        val step = step(landedAt = 1_000L)
        assertEquals(click, step.onClick(3_000L, swap, app))
        // Once only.
        assertEquals(Decision.Ignore, step.onClick(3_100L, swap, app))
    }

    @Test
    fun `a tap somewhere else, or on our own windows, does nothing`() {
        val step = step()
        assertEquals(Decision.Ignore, step.onClick(3_000L, "Button|other|Other", app))
        assertEquals(Decision.Ignore, step.onClick(3_000L, swap, own))
    }

    @Test
    fun `a tap inside the quiet time is held and acted on when it ends`() {
        val step = step(landedAt = 1_000L)
        assertEquals(Decision.Wait, step.onClick(1_400L, swap, app))
        assertEquals(1_100L, step.quietLeft(1_400L))
        assertEquals(Decision.Wait, step.tick(2_400L))
        assertEquals(click, step.tick(2_500L))
    }

    @Test
    fun `nothing advances while the line is spoken - a tap waits for the line to end`() {
        val step = step(landedAt = 1_000L)
        step.speechStarted()
        assertEquals(Decision.Wait, step.onClick(3_000L, swap, app))
        assertEquals(Decision.Wait, step.tick(5_000L))
        assertEquals(click, step.speechEnded(6_000L))
    }

    @Test
    fun `the target app's content changing by more than 15 percent advances, less does not`() {
        val step = step(landedAt = 1_000L)
        // One node of 21 swapped: a 9% difference (a counter ticking, a toast).
        assertEquals(Decision.Ignore, step.onContentChange(3_000L, app, app, changed(1)))
        assertEquals(change, step.onContentChange(3_500L, app, app, changed(8)))
    }

    @Test
    fun `the app handing over to another one is a change of everything`() {
        val step = step(landedAt = 1_000L)
        assertEquals(change, step.onContentChange(3_000L, "com.solanamobile.seedvaultimpl", "com.solanamobile.seedvaultimpl", setOf("a")))
    }

    @Test
    fun `nothing held means nothing to wait for`() {
        val step = step(landedAt = 1_000L)
        assertEquals(Decision.Wait, step.tick(9_000L))
        assertEquals(false, step.holding)
    }

    @Test
    fun `difference is the share of nodes not in both`() {
        assertEquals(0.0, StepAdvance.difference(setOf("a", "b"), setOf("a", "b")), 0.0001)
        assertEquals(1.0, StepAdvance.difference(setOf("a"), setOf("b")), 0.0001)
        assertEquals(0.5, StepAdvance.difference(setOf("a", "b"), setOf("a")), 0.0001)
    }

    @Test
    fun `the wallet's live price and quote refreshing is not the user doing the step`() {
        fun screenAt(price: String, quote: String) = listOf(
            ScreenNode(1, 0, "Text", "Sell", null, null, false, false, false, null, android.graphics.Rect()),
            ScreenNode(2, 0, "Text", price, null, null, false, false, false, null, android.graphics.Rect()),
            ScreenNode(3, 0, "Text", quote, null, null, false, false, false, null, android.graphics.Rect())
        )
        assertEquals(
            StepAdvance.signature(screenAt("$0.00111", "1 SOL ≈ 111.7 USDC")),
            StepAdvance.signature(screenAt("$0.00112", "1 SOL ≈ 111.94 USDC"))
        )
        assertEquals("Text||Balance: #||", StepAdvance.withoutNumbers("Text||Balance: 1.045||"))
    }

    @Test
    fun `with no tap in the app, only a new screen counts - a partial redraw waits for the user`() {
        val step = step(landedAt = 1_000L)
        // Part of the screen redrawn (a quote loading, a banner): 38%, nobody touched anything.
        assertEquals(Decision.Ignore, step.onContentChange(3_000L, app, app, changed(5)))
        assertEquals(0.38, step.lastDifference, 0.01)
        // The user taps something in the app (not the pointed element): now the same change counts.
        assertEquals(Decision.Ignore, step.onClick(3_200L, "Button|max|Max", app))
        assertEquals(change, step.onContentChange(3_400L, app, app, changed(5)))
    }

    @Test
    fun `a whole new screen counts even when the app sent no tap`() {
        val step = step(landedAt = 1_000L)
        assertEquals(change, step.onContentChange(3_000L, app, app, changed(15)))
    }

    @Test
    fun `the box settling uncovers part of the app - that read is the step's screen, not a change`() {
        val step = step(landedAt = 1_000L)
        // Seconds later the box settles into its small form, and the app is read with more of it showing.
        step.ownWindowsChanged(6_000L)
        assertEquals(Decision.Ignore, step.onContentChange(6_300L, app, app, changed(15)))
        // Afterwards the same screen again is no change at all.
        assertEquals(Decision.Ignore, step.onContentChange(9_000L, app, app, changed(15)))
        assertEquals(0.0, step.lastDifference, 0.0001)
    }

    private val typed = Decision.Advance(StepAdvance.Reason.TYPED)

    @Test
    fun `a typing step moves on once the typed value settles - Jupiter's keypad`() {
        val s = step()
        s.expectTyping("0")
        // The user types 0.0009 over a couple of seconds, well after the quiet time.
        val t0 = 5_000L
        assertEquals(StepAdvance.TYPED_SETTLE_MS, s.onValue(t0, "0."))
        s.onValue(t0 + 400, "0.0")
        s.onValue(t0 + 800, "0.00")
        s.onValue(t0 + 1_200, "0.0009")
        // Not yet: still inside the settle time of the last key.
        assertEquals(Decision.Wait, s.tick(t0 + 2_000))
        assertEquals(typed, s.tick(t0 + 1_200 + StepAdvance.TYPED_SETTLE_MS))
    }

    @Test
    fun `a typing step waits for the line to end, and a value put back is not typed`() {
        val s = step()
        s.expectTyping("0")
        s.speechStarted()
        s.onValue(5_000, "0.5")
        assertEquals(Decision.Wait, s.tick(5_000 + StepAdvance.TYPED_SETTLE_MS))
        assertEquals(typed, s.speechEnded(9_000))

        val back = step()
        back.expectTyping("0")
        back.onValue(5_000, "5")
        assertEquals(null, back.onValue(5_500, "0"))
        assertEquals(Decision.Wait, back.tick(5_500 + StepAdvance.TYPED_SETTLE_MS))
    }

    @Test
    fun `a step that does not say to type ignores its field changing`() {
        val s = step()
        assertEquals(null, s.onValue(5_000, "0.5"))
        assertEquals(Decision.Wait, s.tick(5_000 + StepAdvance.TYPED_SETTLE_MS))
    }
}
