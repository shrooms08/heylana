package xyz.heylana.app.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.Base58
import org.sol4k.PublicKey

/**
 * The payment transaction, byte by byte. The expected token account addresses
 * were derived independently (sha256 plus an ed25519 curve check, in Python), so
 * this checks the library's derivation rather than trusting it.
 */
class PaymentTransactionTest {

    private val usdc = PublicKey("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
    private val payer = PublicKey("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM")
    private val treasury = PublicKey("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val reference = PublicKey("4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T")
    private val blockhash = "EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k"

    private fun order(amount: Long = 100_000, program: PublicKey = PaymentTransaction.TOKEN_PROGRAM) =
        PaymentTransaction.Order(payer, usdc, program, treasury, amount, 6, reference, blockhash)

    @Test
    fun `token accounts derive to the independently computed addresses`() {
        assertEquals(
            "FGETo8T8wMcN2wCjav8VK6eh3dLk63evNDPxzLSJra8B",
            PaymentTransaction.associatedTokenAccount(payer, usdc, PaymentTransaction.TOKEN_PROGRAM).toBase58()
        )
        assertEquals(
            "C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH",
            PaymentTransaction.associatedTokenAccount(treasury, usdc, PaymentTransaction.TOKEN_PROGRAM).toBase58()
        )
    }

    @Test
    fun `a Token-2022 mint derives a different account`() {
        assertEquals(
            "GdjpegrtGwU3pgtzPivYVViSA8rmGL248qBVKzsrU3DD",
            PaymentTransaction.associatedTokenAccount(payer, usdc, PaymentTransaction.TOKEN_2022_PROGRAM).toBase58()
        )
    }

    @Test
    fun `transferChecked carries instruction 12, the amount and the decimals`() {
        val ix = PaymentTransaction.instructions(order(amount = 15_000_000))[1]
        val data = ix.data
        assertEquals(10, data.size)
        assertEquals(12.toByte(), data[0])
        // 15,000,000 as little-endian u64.
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0xE1.toByte(), 0xE4.toByte(), 0, 0, 0, 0, 0), data.copyOfRange(1, 9))
        assertEquals(6.toByte(), data[9])
        assertEquals(PaymentTransaction.TOKEN_PROGRAM, ix.programId)
    }

    @Test
    fun `transferChecked moves from the payer's account to the treasury's, signed by the payer`() {
        val keys = PaymentTransaction.instructions(order())[1].keys
        assertEquals("FGETo8T8wMcN2wCjav8VK6eh3dLk63evNDPxzLSJra8B", keys[0].publicKey.toBase58())
        assertTrue(keys[0].writable)
        assertEquals(usdc, keys[1].publicKey)
        assertFalse(keys[1].writable)
        assertEquals("C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH", keys[2].publicKey.toBase58())
        assertTrue(keys[2].writable)
        assertEquals(payer, keys[3].publicKey)
        assertTrue(keys[3].signer)
    }

    @Test
    fun `the reference rides along, read-only and never a signer`() {
        val last = PaymentTransaction.instructions(order())[1].keys.last()
        assertEquals(reference, last.publicKey)
        assertFalse(last.signer)
        assertFalse(last.writable)
    }

    @Test
    fun `the treasury's account is created first if missing, paid for by the user`() {
        val create = PaymentTransaction.instructions(order())[0]
        assertEquals(PaymentTransaction.ASSOCIATED_TOKEN_PROGRAM, create.programId)
        assertArrayEquals(byteArrayOf(1), create.data)
        assertEquals(payer, create.keys[0].publicKey)
        assertTrue(create.keys[0].signer && create.keys[0].writable)
        assertEquals("C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH", create.keys[1].publicKey.toBase58())
        assertEquals(treasury, create.keys[2].publicKey)
        assertEquals(usdc, create.keys[3].publicKey)
        assertEquals(PaymentTransaction.TOKEN_PROGRAM, create.keys[5].publicKey)
    }

    @Test
    fun `the unsigned transaction has one empty signature slot and the payer pays the fee`() {
        val bytes = PaymentTransaction.serializeUnsigned(order())
        assertEquals("one signature slot", 1, bytes[0].toInt())
        assertTrue("the slot is empty", bytes.copyOfRange(1, 65).all { it == 0.toByte() })
        assertEquals("one required signature", 1, bytes[65].toInt())
        val keyCount = bytes[68].toInt()
        val firstKey = bytes.copyOfRange(69, 69 + 32)
        assertEquals("fee payer is the first account", payer.toBase58(), Base58.encode(firstKey))
        val keys = (0 until keyCount).map { Base58.encode(bytes.copyOfRange(69 + 32 * it, 101 + 32 * it)) }
        assertTrue("reference is in the transaction", keys.contains(reference.toBase58()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero amount is never built`() {
        PaymentTransaction.instructions(order(amount = 0))
    }
}
