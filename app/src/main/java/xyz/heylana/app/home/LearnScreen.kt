package xyz.heylana.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.heylana.app.lessons.LessonNote
import xyz.heylana.app.ui.app.FlatPage
import xyz.heylana.app.ui.app.FlatRow
import xyz.heylana.app.ui.app.InnerTopBar
import xyz.heylana.app.ui.app.SectionHead
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana

/** Every word the topic list says. */
object LearnText {
    const val TITLE = "Learn Solana"
    const val LEAD = "Short spoken lessons, with a question after each part. Say skip, slower, example, why or stop at any time."
    const val BUILD = "Build"
    const val INFRASTRUCTURE = "Infrastructure"

    /** "5 short parts". */
    fun parts(note: LessonNote): String = "${note.chunks} short parts"
}

/**
 * The topics, in two tracks: tapping one goes back to Home and starts the lesson there, in
 * the orb's strip and Heylana's voice. The answers are typed or said with the mic.
 */
@Composable
fun LearnScreen(notes: List<LessonNote>, onPick: (LessonNote) -> Unit, onBack: () -> Unit) {
    val palette = LocalHeylana.current
    FlatPage {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.padding(vertical = 14.dp)) { InnerTopBar(onBack) }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                Text(LearnText.TITLE, style = HeylanaType.title, color = palette.ink)
                Text(LearnText.LEAD, style = HeylanaType.bodyLight, color = palette.inkSecondary)
                for ((track, heading) in listOf(LessonNote.BUILD to LearnText.BUILD, LessonNote.INFRASTRUCTURE to LearnText.INFRASTRUCTURE)) {
                    Spacer(Modifier.height(8.dp))
                    SectionHead(heading)
                    notes.filter { it.track == track }.forEachIndexed { i, note ->
                        FlatRow(note.title, subtitle = LearnText.parts(note), letter = "${i + 1}", onClick = { onPick(note) })
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
