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
 * The transfer for a send the user asked for and confirmed: SOL straight from
 * wallet to wallet, or USDC/SKR into the recipient's token account (opening it
 * first if they never held that token — the sender pays that small rent).
 *
 * Built here unsigned. Seed Vault shows it, and only the user can sign it.
 */
object SendTransaction {

    private const val SYSTEM_TRANSFER = 2
    private const val SIGNATURE_BYTES = 64

    data class Order(
        val from: PublicKey,
        val to: PublicKey,
        /** Null for SOL. */
        val mint: PublicKey?,
        val tokenProgram: PublicKey?,
        /** Lamports for SOL, base units for a token. */
        val units: Long,
        val decimals: Int,
        val recentBlockhash: String
    )

    /** System Program transfer: instruction 2, then the lamports as a little-endian u64. */
    fun systemTransfer(from: PublicKey, to: PublicKey, lamports: Long): Instruction {
        require(lamports > 0) { "lamports must be positive" }
        val data = ByteBuffer.allocate(4 + 8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SYSTEM_TRANSFER)
            .putLong(lamports)
            .array()
        return BaseInstruction(
            data,
            listOf(
                AccountMeta(from, signer = true, writable = true),
                AccountMeta(to, signer = false, writable = true)
            ),
            PaymentTransaction.SYSTEM_PROGRAM
        )
    }

    fun instructions(order: Order): List<Instruction> {
        val mint = order.mint ?: return listOf(systemTransfer(order.from, order.to, order.units))
        val program = requireNotNull(order.tokenProgram) { "a token send needs its token program" }
        val toAccount = PaymentTransaction.associatedTokenAccount(order.to, mint, program)
        val fromAccount = PaymentTransaction.associatedTokenAccount(order.from, mint, program)
        return listOf(
            // Does nothing if the account is already there.
            PaymentTransaction.createIdempotent(order.from, toAccount, order.to, mint, program),
            PaymentTransaction.transferChecked(
                source = fromAccount,
                mint = mint,
                destination = toAccount,
                owner = order.from,
                amount = order.units,
                decimals = order.decimals,
                reference = null,
                tokenProgram = program
            )
        )
    }

    fun serializeUnsigned(order: Order): ByteArray {
        val transaction = Transaction(order.recentBlockhash, instructions(order), order.from)
        transaction.addSignature(Base58.encode(ByteArray(SIGNATURE_BYTES)))
        return transaction.serialize()
    }
}
