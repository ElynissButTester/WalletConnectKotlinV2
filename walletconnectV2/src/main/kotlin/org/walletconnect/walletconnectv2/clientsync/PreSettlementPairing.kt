package org.walletconnect.walletconnectv2.clientsync

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.walletconnect.walletconnectv2.outofband.pairing.Pairing

sealed class PreSettlementPairing {
    abstract val id: Int
    abstract val jsonrpc: String
    abstract val method: String
    abstract val params: Pairing

    @JsonClass(generateAdapter = true)
    data class Approve(
        @Json(name = "id")
        override val id: Int,
        @Json(name = "jsonrpc")
        override val jsonrpc: String = "2.0",
        @Json(name = "method")
        override val method: String = "wc_pairingApprove",
        @Json(name = "params")
        override val params: Pairing.Success
    ): PreSettlementPairing()

    data class Reject(
        override val id: Int,
        override val jsonrpc: String = "2.0",
        override val method: String = "wc_pairingReject",
        override val params: Pairing.Failure
    ): PreSettlementPairing()
}
