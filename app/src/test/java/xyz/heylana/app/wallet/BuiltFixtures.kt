package xyz.heylana.app.wallet

import java.util.Base64

/**
 * Unsigned transfers exactly as the worker builds them (worker/test/tx.test.ts holds its
 * builder to these same bytes, which the app's old sol4k builder made).
 */
object BuiltFixtures {
    const val PAYER = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    const val TREASURY = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
    const val FRIEND = "4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T"
    const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val REFERENCE = "Ref1111111111111111111111111111111111111111"

    /** 0.1 USDC from PAYER to TREASURY, with REFERENCE: a Pro payment. */
    val PAY: ByteArray = Base64.getDecoder().decode("AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAYJfowIh2C/3h3dzzLBfyCbgkLuUqrxMfrNiNDqLG0LBvKkTqbyus+/B3iFE3O1Go6HtCYUWNf9k4ccnNp5Dw4bJtPqjPWsrKjNBSB1EhdcQ871Sl3Znt4goWtVJTc485fcZ1IFXCCz6dh0Zlbd9zhVUH+Hq22HUj5Mdqf6NglqmevG+nrzvtutOj1l82qryXQxsbvkwtL24OR8pgIDRS9dYQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABlCEKWr5G3+Ic7N4w43zx6WcdkRe7fhiZKjoAAAAAACMlyWPTiSJ8bs9ECkUjg2DC1oTmdr/EIQEjnvY2+n4WQbd9uHXZaGT2cvhRs7reawctIXtX1s3kTqM9YV+/wCpzEkOkozS44c7s0P8ldozF5ymD02/RsLDbpEpnVXU5rkCBwYAAQMEBQgBAQgFAgQBAAYKDKCGAQAAAAAABg==")

    /** 0.05 SOL from PAYER to FRIEND. */
    val SOL: ByteArray = Base64.getDecoder().decode("AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAEDfowIh2C/3h3dzzLBfyCbgkLuUqrxMfrNiNDqLG0LBvIyHPpa3RheiJOl/YgBPsTX4SLe1GNUyt/1DZVjledbYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAzEkOkozS44c7s0P8ldozF5ymD02/RsLDbpEpnVXU5rkBAgIAAQwCAAAAgPD6AgAAAAA=")

    /** 0.05 USDC from PAYER to FRIEND. */
    val TOKEN: ByteArray = Base64.getDecoder().decode("AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAUIfowIh2C/3h3dzzLBfyCbgkLuUqrxMfrNiNDqLG0LBvLR9fE19GbyQgzJH3P+O5JTxo2ZMOJst1OXI8hEXWMv09PqjPWsrKjNBSB1EhdcQ871Sl3Znt4goWtVJTc485fcMhz6Wt0YXoiTpf2IAT7E1+Ei3tRjVMrf9Q2VY5XnW2DG+nrzvtutOj1l82qryXQxsbvkwtL24OR8pgIDRS9dYQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAjJclj04kifG7PRApFI4NgwtaE5na/xCEBI572Nvp+FkG3fbh12Whk9nL4UbO63msHLSF7V9bN5E6jPWFfv8AqcxJDpKM0uOHO7ND/JXaMxecpg9Nv0bCw26RKZ1V1Oa5AgYGAAEDBAUHAQEHBAIEAQAKDFDDAAAAAAAABg==")

    val preview = TransferPreview(
        from = "9WzD…AWWM", fromLabel = "your wallet", to = "4Nd1…DB4T", toLabel = "a wallet", amount = "0.05",
        token = "USDC", feeSol = "0.000005", accountRentSol = "0", createsAccount = false,
        programs = listOf("Associated Token Account Program", "SPL Token Program"), cluster = "devnet"
    )

    fun built(bytes: ByteArray?, simulation: SimulationResult = SimulationResult.Passed) =
        BuiltTransfer("send", preview, simulation, bytes, 150)
}
