package xyz.heylana.app.actions

import java.util.Locale

/**
 * Numbers as they are said aloud, turned into the digits they stand for, so a number
 * spoken to the recogniser counts as said: "oh eight hundred one two three" is 0800123,
 * "double four" is 44, "twenty one" is 21. Typed digits in the same breath join the run:
 * "0800 one two three" is 0800123.
 */
object SpokenNumbers {

    private val ONES = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "nought" to "0", "nil" to "0",
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9"
    )
    private val TEENS = mapOf(
        "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13", "fourteen" to "14",
        "fifteen" to "15", "sixteen" to "16", "seventeen" to "17", "eighteen" to "18", "nineteen" to "19"
    )
    private val TENS = mapOf(
        "twenty" to "2", "thirty" to "3", "forty" to "4", "fifty" to "5",
        "sixty" to "6", "seventy" to "7", "eighty" to "8", "ninety" to "9"
    )

    /** Every digit the text holds, spoken or typed, in the order said, as one string. */
    fun digits(text: String): String {
        val tokens = text.lowercase(Locale.US).replace('-', ' ').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val out = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val next = tokens.getOrNull(i + 1)
            when {
                token.all(Char::isDigit) -> out.append(token)
                (token == "double" || token == "triple") && next != null && ONES.containsKey(next) -> {
                    repeat(if (token == "double") 2 else 3) { out.append(ONES.getValue(next)) }
                    i++
                }
                // "o" alone is only a zero between other digits, never the letter in a sentence.
                token == "o" -> if (out.isNotEmpty() && next != null && (ONES.containsKey(next) || next.all(Char::isDigit))) out.append("0")
                ONES.containsKey(token) -> out.append(ONES.getValue(token))
                TEENS.containsKey(token) -> out.append(TEENS.getValue(token))
                TENS.containsKey(token) -> {
                    val unit = next?.let { ONES[it] }?.takeIf { next != "oh" && next != "o" && next != "zero" }
                    out.append(TENS.getValue(token)).append(unit ?: "0")
                    if (unit != null) i++
                }
                // "eight hundred" is 800, "two thousand" 2000: zeros after the digit said.
                token == "hundred" && out.isNotEmpty() -> out.append("00")
                token == "thousand" && out.isNotEmpty() -> out.append("000")
            }
            i++
        }
        return out.toString()
    }
}
