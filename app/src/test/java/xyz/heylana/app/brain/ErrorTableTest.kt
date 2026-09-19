package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in error table: found in every form people paste, and exactly what the sources say. */
class ErrorTableTest {

    private fun name(text: String) = ErrorTable.find(text)?.name

    @Test
    fun `every Anchor framework error is there, numbered as in Anchor v1_2_0`() {
        assertEquals(82, ErrorTable.anchorCount)
        assertEquals("3003", ErrorTable.find("Error Number: 3003")?.code)
        assertEquals("AccountDidNotDeserialize", name("Error Number: 3003"))
        assertEquals("ConstraintSeeds", name("Error Number: 2006"))
        assertEquals("DeclaredProgramIdMismatch", name("Error Number: 4100"))
        assertEquals("ConstraintMintPausableAuthority", name("Error Number: 2044"))
    }

    @Test
    fun `an Anchor error is found however it is pasted or asked about`() {
        val log = "Program log: AnchorError caused by account: vault. Error Code: AccountDidNotDeserialize. " +
            "Error Number: 3003. Error Message: Failed to deserialize the account."
        assertEquals("AccountDidNotDeserialize", name(log))
        assertEquals("AccountDidNotDeserialize", name("what causes AccountDidNotDeserialize"))
        // 0xbbb is 3003 in hex, as a failed transaction prints it.
        assertEquals("AccountDidNotDeserialize", name("Error processing Instruction 0: custom program error: 0xbbb"))
        assertEquals("ConstraintSeeds", name("custom program error: 0x7d6"))
    }

    @Test
    fun `a program's own Anchor error is numbered from 6000`() {
        val known = ErrorTable.find("custom program error: 0x1771")!!
        assertEquals("6001", known.code)
        assertTrue(known.cause.contains("error number 1"))
    }

    @Test
    fun `a small number is Token's or System's only when the text names the program`() {
        assertEquals("InsufficientFunds", name("Program TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA failed: custom program error: 0x1"))
        assertEquals("AccountAlreadyInUse", name("Program 11111111111111111111111111111111 failed: custom program error: 0x0"))
        assertNull(name("custom program error: 0x1"))
        assertTrue(ErrorTable.looksLikeError("custom program error: 0x1"))
    }

    @Test
    fun `runtime, wallet and Seed Vault errors by what they print`() {
        assertEquals("Blockhash not found", name("SendTransactionError: Transaction simulation failed: Blockhash not found"))
        assertEquals("Attempt to debit an account but found no record of a prior credit", name("Attempt to debit an account but found no record of a prior credit."))
        assertEquals("Computational budget exceeded", name("Program failed: Computational budget exceeded"))
        assertEquals("Missing required signature", name("Error processing Instruction 1: missing required signature for instruction"))
        assertEquals("ERROR_NOT_SIGNED", name("JsonRpc20RemoteException: ERROR_NOT_SIGNED"))
        assertEquals("RESULT_INVALID_AUTH_TOKEN", name("Seed Vault returned RESULT_INVALID_AUTH_TOKEN"))
    }

    @Test
    fun `the line gives cause and usual fix, names the docs, and the link is a chip`() {
        val known = ErrorTable.find("AccountDidNotDeserialize")!!
        val line = ErrorTable.line(known)
        assertTrue(line.startsWith("AccountDidNotDeserialize (3003): "))
        assertTrue(line.contains("Usual fix: "))
        assertTrue(line, line.endsWith("The Anchor docs have more."))
        assertFalse(line.contains("http"))
        assertEquals(Source("Anchor docs: errors", "https://www.anchor-lang.com/docs/features/errors"), ErrorTable.source(known))
        assertEquals("Anchor docs: account constraints", ErrorTable.source(ErrorTable.find("ConstraintSeeds")!!).title)
    }

    @Test
    fun `ordinary questions are not errors, and unknown error text still reads as one`() {
        for (q in listOf("how do priority fees work", "what's on my screen", "tell me a joke", "send 1 SOL to bob.sol", "what is a PDA")) {
            assertNull(q, ErrorTable.find(q))
            assertFalse(q, ErrorTable.looksLikeError(q))
        }
        assertNull(name("Program log: Error: invalid swap route"))
        assertTrue(ErrorTable.looksLikeError("Program log: Error: invalid swap route"))
        assertTrue(ErrorTable.asksAboutError("explain this error"))
        assertTrue(ErrorTable.asksAboutError("why did my transaction fail?"))
        assertFalse(ErrorTable.asksAboutError("what does this button do"))
    }

    @Test
    fun `an unknown error goes to the model with the knowledge base and a way out`() {
        val message = HeylanaPrompt.errorMessage("Program log: Error: invalid swap route", "what does this mean")
        assertTrue(message.contains("search_solana_kb"))
        assertTrue(message.contains("Solana Stack Exchange"))
        assertTrue(message.contains("in cite, never in say"))
        assertTrue(message.endsWith("User asks: what does this mean"))
    }
}
