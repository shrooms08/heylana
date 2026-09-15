package xyz.heylana.app.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SendTransactionTest {

    private val usdc = PublicKey("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
    private val from = PublicKey("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM")
    private val to = PublicKey("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val blockhash = "EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k"

    private fun order(mint: PublicKey?, units: Long, decimals: Int = 6) = SendTransaction.Order(
        from = from, to = to, mint = mint,
        tokenProgram = if (mint == null) null else PaymentTransaction.TOKEN_PROGRAM,
        units = units, decimals = decimals, recentBlockhash = blockhash
    )

    @Test
    fun `a SOL send is one system transfer of the exact lamports`() {
        val instructions = SendTransaction.instructions(order(mint = null, units = 250_000_000, decimals = 9))
        assertEquals(1, instructions.size)
        val transfer = instructions[0]
        assertEquals(PaymentTransaction.SYSTEM_PROGRAM, transfer.programId)
        val expected = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(250_000_000).array()
        assertArrayEquals(expected, transfer.data)
        assertEquals(from, transfer.keys[0].publicKey)
        assertTrue(transfer.keys[0].signer && transfer.keys[0].writable)
        assertEquals(to, transfer.keys[1].publicKey)
        assertFalse(transfer.keys[1].signer)
        assertTrue(transfer.keys[1].writable)
    }

    @Test
    fun `a USDC send opens the recipient's account if needed, then transfers, with no reference`() {
        val instructions = SendTransaction.instructions(order(mint = usdc, units = 50_000))
        assertEquals(2, instructions.size)
        val (create, transfer) = instructions
        assertEquals(PaymentTransaction.ASSOCIATED_TOKEN_PROGRAM, create.programId)
        // The same token-account addresses the payment tests checked independently.
        assertEquals("C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH", create.keys[1].publicKey.toBase58())
        assertEquals(4, transfer.keys.size)
        assertEquals("FGETo8T8wMcN2wCjav8VK6eh3dLk63evNDPxzLSJra8B", transfer.keys[0].publicKey.toBase58())
        assertEquals("C4PRXFV6Gf5mytVZb6RoeLsG8CjcFWzR2EJ3dvwPTUJH", transfer.keys[2].publicKey.toBase58())
        assertEquals(from, transfer.keys[3].publicKey)
        assertTrue(transfer.keys[3].signer)
        assertEquals(12.toByte(), transfer.data[0])
    }

    @Test
    fun `the unsigned transaction has one empty signature slot for Seed Vault to fill`() {
        val bytes = SendTransaction.serializeUnsigned(order(mint = null, units = 1_000, decimals = 9))
        assertEquals(1.toByte(), bytes[0])
        assertTrue((1..64).all { bytes[it] == 0.toByte() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nothing is sent for zero`() {
        SendTransaction.instructions(order(mint = null, units = 0, decimals = 9))
    }
}
