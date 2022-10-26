package com.walletconnect.android.pairing

import com.walletconnect.android.Core
import com.walletconnect.android.internal.common.model.*
import com.walletconnect.foundation.common.model.Topic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

interface PairingInterface {
    val topicExpiredFlow : SharedFlow<Topic>
    val findWrongMethodsFlow: Flow<InternalError>

    fun ping(ping: Core.Params.Ping, pairingPing: Core.Listeners.PairingPing? = null)

    fun create(onError: (Core.Model.Error) -> Unit = {}): Core.Model.Pairing?

    fun pair(pair: Core.Params.Pair, onError: (Core.Model.Error) -> Unit = {})

    fun getPairings(): List<Core.Model.Pairing>

    fun disconnect(topic: String, onError: (Core.Model.Error) -> Unit = {})

    //  idea: --- I think those below shouldn't be accessible by SDK consumers.
    fun activate(topic: String, onError: (Core.Model.Error) -> Unit = {})

    fun updateExpiry(topic: String, expiry: Expiry, onError: (Core.Model.Error) -> Unit = {})

    fun updateMetadata(topic: String, metadata: Core.Model.AppMetaData, metaDataType: AppMetaDataType, onError: (Core.Model.Error) -> Unit = {})

    fun register(vararg method: String)

    interface Delegate {
        fun onPairingDelete(deletedPairing: Core.Model.DeletedPairing)
    }
}