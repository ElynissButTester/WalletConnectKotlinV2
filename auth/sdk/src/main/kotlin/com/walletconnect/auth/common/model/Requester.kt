@file:JvmSynthetic

package com.walletconnect.auth.common.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.walletconnect.android.common.model.metadata.PeerMetaData

@JsonClass(generateAdapter = true)
internal data class Requester(
    @Json(name = "publicKey")
    val publicKey: String,
    @Json(name = "metadata")
    val metadata: PeerMetaData,
)