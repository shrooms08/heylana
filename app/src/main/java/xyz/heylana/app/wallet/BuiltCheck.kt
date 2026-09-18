package xyz.heylana.app.wallet

import org.sol4k.Base58
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The app's own look at the bytes the worker built, before they go to Seed Vault.
 *
 * The worker builds and simulates; the app still checks that what it is about to hand
 * the wallet is the transfer the user confirmed on the strip, and nothing else: the
 * user pays the fee and is the only signer; only the System, Token, Token-2022 and
 * Associated Token Account programs are touched; there is exactly one transfer, of
 * exactly the confirmed amount, to exactly the confirmed recipient (or their token
 * account for that mint). Anything else and the wallet is not opened.
 *
 * Legacy messages only, which is all the worker builds.
 */
object BuiltCheck {

    val SYSTEM_PROGRAM = PublicKey("11111111111111111111111111111111")
    val TOKEN_PROGRAM = PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val TOKEN_2022_PROGRAM = PublicKey("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    val ASSOCIATED_TOKEN_PROGRAM = PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")

    private const val SYSTEM_TRANSFER = 2
    private const val TRANSFER_CHECKED = 12
    private const val CREATE_IDEMPOTENT = 1
    private const val KEY_BYTES = 32
    private const val SIGNATURE_BYTES = 64

    /** What the user confirmed. [mint] null is SOL. */
    data class Expected(
        val from: String,
        val to: String,
        val mint: String?,
        val tokenProgram: String?,
        val units: Long
    )

    sealed interface Verdict {
        data object Matches : Verdict
        data class Differs(val why: String) : Verdict
    }

    /** Where [owner] holds [mint], under whichever token program owns the mint. */
    fun associatedTokenAccount(owner: PublicKey, mint: PublicKey, tokenProgram: PublicKey): PublicKey =
        PublicKey.findProgramAddress(listOf(owner, tokenProgram, mint), ASSOCIATED_TOKEN_PROGRAM).publicKey

    fun check(transaction: ByteArray, expected: Expected): Verdict = runCatching { checkOrThrow(transaction, expected) }
        .getOrElse { Verdict.Differs("unreadable") }

    private fun checkOrThrow(bytes: ByteArray, expected: Expected): Verdict {
        val reader = Reader(bytes)
        val slots = reader.compact()
        if (slots != 1) return Verdict.Differs("signatures=$slots")
        reader.skip(SIGNATURE_BYTES)
        if (reader.peek() and 0x80 != 0) return Verdict.Differs("versioned")

        val required = reader.byte()
        reader.byte() // read-only signed
        reader.byte() // read-only unsigned
        if (required != 1) return Verdict.Differs("signers=$required")
        val keys = List(reader.compact()) { Base58.encode(reader.take(KEY_BYTES)) }
        reader.skip(KEY_BYTES) // blockhash
        if (keys.firstOrNull() != expected.from) return Verdict.Differs("fee_payer")

        val allowed = setOf(SYSTEM_PROGRAM, TOKEN_PROGRAM, TOKEN_2022_PROGRAM, ASSOCIATED_TOKEN_PROGRAM).map { it.toBase58() }
        var transfers = 0
        repeat(reader.compact()) {
            val program = keys[reader.byte()]
            val accounts = List(reader.compact()) { keys[reader.byte()] }
            val data = reader.take(reader.compact())
            if (program !in allowed) return Verdict.Differs("program")
            when (program) {
                ASSOCIATED_TOKEN_PROGRAM.toBase58() ->
                    if (!(data.size == 1 && data[0].toInt() == CREATE_IDEMPOTENT)) return Verdict.Differs("ata_instruction")
                SYSTEM_PROGRAM.toBase58() -> {
                    val le = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    if (data.size != 12 || le.getInt(0) != SYSTEM_TRANSFER) return Verdict.Differs("system_instruction")
                    if (expected.mint != null) return Verdict.Differs("sol_in_token_send")
                    if (accounts.getOrNull(0) != expected.from || accounts.getOrNull(1) != expected.to) {
                        return Verdict.Differs("recipient")
                    }
                    if (le.getLong(4) != expected.units) return Verdict.Differs("amount")
                    transfers++
                }
                else -> {
                    val mint = expected.mint ?: return Verdict.Differs("token_in_sol_send")
                    val tokenProgram = expected.tokenProgram ?: TOKEN_PROGRAM.toBase58()
                    if (program != tokenProgram) return Verdict.Differs("token_program")
                    if (data.size != 10 || data[0].toInt() != TRANSFER_CHECKED) return Verdict.Differs("token_instruction")
                    val destination = associatedTokenAccount(PublicKey(expected.to), PublicKey(mint), PublicKey(tokenProgram))
                    val source = associatedTokenAccount(PublicKey(expected.from), PublicKey(mint), PublicKey(tokenProgram))
                    if (accounts.getOrNull(0) != source.toBase58()) return Verdict.Differs("source")
                    if (accounts.getOrNull(1) != mint) return Verdict.Differs("mint")
                    if (accounts.getOrNull(2) != destination.toBase58()) return Verdict.Differs("recipient")
                    if (accounts.getOrNull(3) != expected.from) return Verdict.Differs("owner")
                    if (ByteBuffer.wrap(data, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long != expected.units) {
                        return Verdict.Differs("amount")
                    }
                    transfers++
                }
            }
        }
        if (reader.remaining() != 0) return Verdict.Differs("trailing_bytes")
        return if (transfers == 1) Verdict.Matches else Verdict.Differs("transfers=$transfers")
    }

    private class Reader(private val bytes: ByteArray) {
        private var at = 0
        fun peek(): Int = bytes[at].toInt() and 0xff
        fun byte(): Int = bytes[at++].toInt() and 0xff
        fun skip(n: Int) {
            require(at + n <= bytes.size)
            at += n
        }
        fun take(n: Int): ByteArray {
            require(at + n <= bytes.size)
            return bytes.copyOfRange(at, at + n).also { at += n }
        }
        fun remaining(): Int = bytes.size - at

        /** Solana's compact-u16. */
        fun compact(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val b = byte()
                value = value or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }
    }
}
