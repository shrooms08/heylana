package xyz.heylana.app.actions

/** A contact with a phone number, as read from the phone's own contacts. Never leaves the phone. */
data class Contact(val name: String, val number: String)

/**
 * Which contact the user meant by the name they said: "Ada" finds "Ada Obi". The same
 * scoring as [AppMatcher] — the exact name first, then a name holding every word said —
 * and never a guess between two people who fit equally well. One person with several
 * numbers is still one person.
 */
object ContactMatcher {

    sealed interface Match {
        data class Found(val contact: Contact) : Match
        data class Ambiguous(val first: String, val second: String) : Match
        data object None : Match
    }

    fun best(said: String, contacts: List<Contact>): Match {
        val wanted = AppMatcher.words(said)
        if (wanted.isEmpty()) return Match.None
        val scored = contacts
            .groupBy { it.name.trim() }
            .map { (name, entries) -> Triple(name, entries.first(), AppMatcher.score(wanted, AppMatcher.words(name))) }
            .filter { it.third > 0 }
            .sortedByDescending { it.third }
        val top = scored.firstOrNull() ?: return Match.None
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && runnerUp.third == top.third) return Match.Ambiguous(top.first, runnerUp.first)
        return Match.Found(top.second.copy(name = top.first))
    }
}
