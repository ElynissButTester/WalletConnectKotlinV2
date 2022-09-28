@file:JvmSynthetic

package com.walletconnect.auth.json_rpc.domain

import com.walletconnect.android.common.crypto.Codec
import com.walletconnect.android.common.relay.RelayConnectionInterface
import com.walletconnect.auth.common.json_rpc.AuthRpc
import com.walletconnect.auth.common.model.JsonRpcHistoryEntry
import com.walletconnect.auth.json_rpc.data.JsonRpcSerializer
import com.walletconnect.auth.json_rpc.model.JsonRpcMethod
import com.walletconnect.auth.json_rpc.model.toEntry

internal class JsonRpcInteractor(
    relay: RelayConnectionInterface,
    chaChaPolyCodec: Codec,
    private val jsonRpcHistory: JsonRpcHistory,
    private val serializer: JsonRpcSerializer
) : BaseJsonRpcInteractor(relay, serializer, chaChaPolyCodec, jsonRpcHistory) {

    fun getPendingJsonRpcHistoryEntries(): List<JsonRpcHistoryEntry> =
        jsonRpcHistory.getListOfPendingRecords()
            .filter { record -> record.method == JsonRpcMethod.WC_AUTH_REQUEST }
            .filter { record -> serializer.tryDeserialize<AuthRpc.AuthRequest>(record.body) != null }
            .map { record -> record.toEntry(serializer.tryDeserialize<AuthRpc.AuthRequest>(record.body)!!.params) }

    fun getPendingJsonRpcHistoryEntryById(id: Long): JsonRpcHistoryEntry? {
        val record: JsonRpcHistoryRecord? = jsonRpcHistory.getPendingRecordById(id)
        var entry: JsonRpcHistoryEntry? = null

        if (record != null) {
            val authRequest: AuthRpc.AuthRequest? = serializer.tryDeserialize<AuthRpc.AuthRequest>(record.body)
            if (authRequest != null) {
                entry = record.toEntry(authRequest.params)
            }
        }

        return entry
    }

    fun getResponseById(id: Long): com.walletconnect.android.common.JsonRpcResponse? {
        val record: JsonRpcHistoryRecord? = jsonRpcHistory.getRecordById(id)
        var jsonRpcResponse: com.walletconnect.android.common.JsonRpcResponse? = null

        if (record != null) {
            record.response?.let { responseJson ->
                serializer.tryDeserialize<com.walletconnect.android.common.JsonRpcResponse.JsonRpcResult>(responseJson)?.let { jsonRpcResult ->
                    jsonRpcResponse = jsonRpcResult
                } ?: serializer.tryDeserialize<com.walletconnect.android.common.JsonRpcResponse.JsonRpcError>(responseJson)?.let { jsonRpcError ->
                    jsonRpcResponse = jsonRpcError
                }
            }
        }

        return jsonRpcResponse
    }
}