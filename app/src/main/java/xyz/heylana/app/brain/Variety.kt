package xyz.heylana.app.brain

import kotlin.random.Random

/**
 * Jokes, fun facts, riddles, stories and compliments come out the same every time unless
 * something nudges them: asked fresh, the model reaches for its favourite (on the Seeker,
 * the scarecrow "outstanding in his field", two fresh starts out of three). So those
 * questions carry an instruction to be fresh and a random seed word to build on.
 */
object Variety {

    private val WANTS = Regex(
        "\\b(joke|jokes|pun|puns|fun fact|funny|riddle|story|compliment|pick-?up line|make me (laugh|smile))\\b",
        RegexOption.IGNORE_CASE
    )

    /** Everyday words with plenty to say about them, and nothing sensitive. */
    val SEEDS: List<String> = listOf(
        "penguin", "bicycle", "volcano", "library", "pineapple", "astronaut", "umbrella", "lighthouse",
        "octopus", "marathon", "sandwich", "dinosaur", "elevator", "cactus", "violin", "submarine",
        "sunflower", "moon", "keyboard", "taxi", "giraffe", "pizza", "chess", "tornado", "snail",
        "ninja", "cookie", "robot", "pirate", "cloud", "bakery", "jungle", "museum", "dragon", "camel",
        "detective", "battery", "compass", "kangaroo", "trampoline", "wizard", "mango", "igloo",
        "rocket", "hammock", "zebra", "lemonade", "skateboard", "owl", "spaghetti", "karaoke",
        "suitcase", "glacier", "flamingo", "calendar", "orchestra", "sock", "treasure", "hedgehog", "comet"
    )

    fun wantsVariety(question: String): Boolean = WANTS.containsMatchIn(question)

    fun seedWord(random: Random = Random.Default): String = SEEDS[random.nextInt(SEEDS.size)]

    /** Goes with the question: be fresh, and build on the seed. */
    fun line(seed: String): String =
        "Make it fresh, not the first one that comes to mind or a well-worn classic. Build it around this word: $seed."
}
