package xyz.heylana.app.wallet

import org.sol4k.AccountMeta
import org.sol4k.Base58
import org.sol4k.PublicKey
import org.sol4k.Transaction
import org.sol4k.instruction.BaseInstruction
import org.sol4k.instruction.Instruction
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The one transaction Heylana ever asks a wallet to sign: paying for Pro.
 *
 * Two instructions, nothing else:
 *
 *  1. create the treasury's token account for this token if it does not exist
 *     yet — the idempotent form, so it is a no-op when it does, and paid for by
 *     the user as the fee payer;
 *  2. a `transferChecked` of exactly the quoted amount from the user's token
 *     account to the treasury's, with the quote's reference address added as a
 *     read-only account so the worker can find and match this exact payment.
 *
 * It is built here and signed and sent by the wallet. Heylana holds no key and
 * never signs anything itself.
 */
object PaymentTransaction {

    val TOKEN_PROGRAM = PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val TOKEN_2022_PROGRAM = PublicKey("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    val ASSOCIATED_TOKEN_PROGRAM = PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val SYSTEM_PROGRAM = PublicKey("11111111111111111111111111111111")

    /** SPL Token's instruction number for transferChecked. */
    private const val TRANSFER_CHECKED: Byte = 12

    /** The associated token program's instruction number for CreateIdempotent. */
    private const val CREATE_IDEMPOTENT: Byte = 1

    private const val SIGNATURE_BYTES = 64

    /** Where [owner] holds [mint], under whichever token program owns the mint. */
    fun associatedTokenAccount(owner: PublicKey, mint: PublicKey, tokenProgram: PublicKey): PublicKey =
        PublicKey.findProgramAddress(listOf(owner, tokenProgram, mint), ASSOCIATED_TOKEN_PROGRAM).publicKey

    /** Creates [owner]'s token account for [mint] if missing; does nothing if it exists. */
    fun createIdempotent(
        payer: PublicKey,
        account: PublicKey,
        owner: PublicKey,
        mint: PublicKey,
        tokenProgram: PublicKey
    ): Instruction = BaseInstruction(
        byteArrayOf(CREATE_IDEMPOTENT),
        listOf(
            AccountMeta(payer, signer = true, writable = true),
            AccountMeta(account, signer = false, writable = true),
            AccountMeta(owner, signer = false, writable = false),
            AccountMeta(mint, signer = false, writable = false),
            AccountMeta(SYSTEM_PROGRAM, signer = false, writable = false),
            AccountMeta(tokenProgram, signer = false, writable = false)
        ),
        ASSOCIATED_TOKEN_PROGRAM
    )

    /**
     * `transferChecked`: instruction 12, the amount as a little-endian u64, then
     * the decimals as one byte. The reference goes last, read-only and not a
     * signer — the token program ignores accounts past the ones it needs.
     */
    fun transferChecked(
        source: PublicKey,
        mint: PublicKey,
        destination: PublicKey,
        owner: PublicKey,
        amount: Long,
        decimals: Int,
        reference: PublicKey,
        tokenProgram: PublicKey
    ): Instruction {
        require(amount > 0) { "amount must be positive" }
        require(decimals in 0..255) { "decimals must fit in a byte" }
        val data = ByteBuffer.allocate(1 + 8 + 1).order(ByteOrder.LITTLE_ENDIAN)
            .put(TRANSFER_CHECKED)
            .putLong(amount)
            .put(decimals.toByte())
            .array()
        return BaseInstruction(
            data,
            listOf(
                AccountMeta(source, signer = false, writable = true),
                AccountMeta(mint, signer = false, writable = false),
                AccountMeta(destination, signer = false, writable = true),
                AccountMeta(owner, signer = true, writable = false),
                AccountMeta(reference, signer = false, writable = false)
            ),
            tokenProgram
        )
    }

    /** Everything the quote says, ready to hand to the wallet unsigned. */
    data class Order(
        val payer: PublicKey,
        val mint: PublicKey,
        val tokenProgram: PublicKey,
        val treasury: PublicKey,
        val amount: Long,
        val decimals: Int,
        val reference: PublicKey,
        val recentBlockhash: String
    )

    fun instructions(order: Order): List<Instruction> {
        val treasuryAccount = associatedTokenAccount(order.treasury, order.mint, order.tokenProgram)
        val payerAccount = associatedTokenAccount(order.payer, order.mint, order.tokenProgram)
        return listOf(
            createIdempotent(order.payer, treasuryAccount, order.treasury, order.mint, order.tokenProgram),
            transferChecked(
                source = payerAccount,
                mint = order.mint,
                destination = treasuryAccount,
                owner = order.payer,
                amount = order.amount,
                decimals = order.decimals,
                reference = order.reference,
                tokenProgram = order.tokenProgram
            )
        )
    }

    /**
     * The wire bytes of the unsigned transaction. One all-zero signature slot is
     * put in for the fee payer, which is the shape wallets expect to fill.
     */
    fun serializeUnsigned(order: Order): ByteArray {
        val transaction = Transaction(order.recentBlockhash, instructions(order), order.payer)
        transaction.addSignature(Base58.encode(ByteArray(SIGNATURE_BYTES)))
        return transaction.serialize()
    }
}
