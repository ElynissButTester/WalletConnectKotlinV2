@file:JvmSynthetic

package com.walletconnect.auth.engine.domain

import com.walletconnect.android.internal.common.JsonRpcResponse
import com.walletconnect.android.internal.common.crypto.KeyManagementRepository
import com.walletconnect.android.internal.common.exception.WalletConnectException
import com.walletconnect.android.impl.common.SDKError
import com.walletconnect.android.impl.common.model.ConnectionState
import com.walletconnect.android.impl.common.model.type.EngineEvent
import com.walletconnect.android.internal.common.scope
import com.walletconnect.android.impl.utils.DAY_IN_SECONDS
import com.walletconnect.android.impl.utils.Logger
import com.walletconnect.android.impl.utils.MONTH_IN_SECONDS
import com.walletconnect.android.internal.common.model.*
import com.walletconnect.android.pairing.PairingInterface
import com.walletconnect.android.pairing.toClient
import com.walletconnect.auth.client.mapper.toCommon
import com.walletconnect.auth.common.exceptions.InvalidCacaoException
import com.walletconnect.auth.common.exceptions.MissingAuthRequestException
import com.walletconnect.auth.common.exceptions.MissingIssuerException
import com.walletconnect.auth.common.exceptions.PeerError
import com.walletconnect.auth.common.json_rpc.AuthParams
import com.walletconnect.auth.common.json_rpc.AuthRpc
import com.walletconnect.auth.common.model.*
import com.walletconnect.auth.engine.mapper.toCacaoPayload
import com.walletconnect.auth.engine.mapper.toFormattedMessage
import com.walletconnect.auth.engine.mapper.toPendingRequest
import com.walletconnect.auth.json_rpc.domain.GetPendingJsonRpcHistoryEntriesUseCase
import com.walletconnect.auth.json_rpc.domain.GetPendingJsonRpcHistoryEntryByIdUseCase
import com.walletconnect.auth.json_rpc.model.JsonRpcMethod
import com.walletconnect.auth.signature.CacaoType
import com.walletconnect.auth.signature.cacao.CacaoVerifier
import com.walletconnect.foundation.common.model.PublicKey
import com.walletconnect.foundation.common.model.Topic
import com.walletconnect.foundation.common.model.Ttl
import com.walletconnect.util.generateId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal class AuthEngine(
    private val relayer: JsonRpcInteractorInterface,
    private val getPendingJsonRpcHistoryEntriesUseCase: GetPendingJsonRpcHistoryEntriesUseCase,
    private val getPendingJsonRpcHistoryEntryByIdUseCase: GetPendingJsonRpcHistoryEntryByIdUseCase,
    private val crypto: KeyManagementRepository,
    private val pairingInterface: PairingInterface,
    private val selfAppMetaData: AppMetaData,
    private val issuer: Issuer?,
    private val cacaoVerifier: CacaoVerifier
) {
    private val _engineEvent: MutableSharedFlow<EngineEvent> = MutableSharedFlow()
    val engineEvent: SharedFlow<EngineEvent> = _engineEvent.asSharedFlow()

    // idea: If we need responseTopic persistence throughout app terminations this is not sufficient. Decide after Alpha
    private val pairingTopicToResponseTopicMap: MutableMap<Topic, Topic> = mutableMapOf()

    init {
        resubscribeToSequences()
        collectJsonRpcRequests()
        collectJsonRpcResponses()
        collectInternalErrors()

        pairingInterface.register(
            JsonRpcMethod.WC_AUTH_REQUEST
        )
    }

    internal fun handleInitializationErrors(onError: (WalletConnectException) -> Unit) {
        relayer.initializationErrorsFlow.onEach { walletConnectException -> onError(walletConnectException) }.launchIn(scope)
    }

    internal fun request(
        payloadParams: PayloadParams,
        pairing: Pairing,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        // For Alpha we are assuming not authenticated only todo: Remove comment after Alpha
        val responsePublicKey: PublicKey = crypto.generateKeyPair()
        val responseTopic: Topic = crypto.getTopicFromKey(responsePublicKey)
        val authParams: AuthParams.RequestParams = AuthParams.RequestParams(Requester(responsePublicKey.keyAsHex, selfAppMetaData), payloadParams)
        val authRequest: AuthRpc.AuthRequest = AuthRpc.AuthRequest(generateId(), params = authParams)
        val irnParams = IrnParams(Tags.AUTH_REQUEST, Ttl(DAY_IN_SECONDS), true)
        val pairingTopic = pairing.topic

        crypto.setSelfParticipant(responsePublicKey, responseTopic)
        relayer.publishJsonRpcRequests(pairingTopic, irnParams, authRequest,
            onSuccess = {
                Logger.log("Auth request sent successfully on topic:${pairingTopic}, awaiting response on topic:$responseTopic") // todo: Remove after Alpha
                relayer.subscribe(responseTopic)
                pairingTopicToResponseTopicMap[pairingTopic] = responseTopic
                onSuccess()
            },
            onFailure = { error ->
                Logger.error("Failed to send a auth request: $error")
                onFailure(error)
            }
        )
    }

    internal fun respond(
        respond: Respond,
        onFailure: (Throwable) -> Unit,
    ) {
        val jsonRpcHistoryEntry = getPendingJsonRpcHistoryEntryByIdUseCase(respond.id)

        if (jsonRpcHistoryEntry == null) {
            Logger.error(MissingAuthRequestException.message)
            onFailure(MissingAuthRequestException)
            return
        }

        val authParams: AuthParams.RequestParams = jsonRpcHistoryEntry.params
        val response: JsonRpcResponse = when (respond) {
            is Respond.Error -> JsonRpcResponse.JsonRpcError(respond.id, error = JsonRpcResponse.Error(respond.code, respond.message))
            is Respond.Result -> {
                val issuer: Issuer = issuer ?: throw MissingIssuerException
                val payload: Cacao.Payload = authParams.payloadParams.toCacaoPayload(issuer)
                val cacao = Cacao(CacaoType.EIP4361.toHeader(), payload, respond.signature.toCommon())
                val responseParams = AuthParams.ResponseParams(cacao.header, cacao.payload, cacao.signature)

                if (!cacaoVerifier.verify(cacao)) throw InvalidCacaoException
                JsonRpcResponse.JsonRpcResult(respond.id, result = responseParams)
            }
        }

        val receiverPublicKey = PublicKey(authParams.requester.publicKey)
        val senderPublicKey: PublicKey = crypto.generateKeyPair()
        val symmetricKey: SymmetricKey = crypto.generateSymmetricKeyFromKeyAgreement(senderPublicKey, receiverPublicKey)
        val responseTopic: Topic = crypto.getTopicFromKey(receiverPublicKey)
        crypto.setSymmetricKey(responseTopic, symmetricKey)

        val irnParams = IrnParams(Tags.AUTH_REQUEST_RESPONSE, Ttl(DAY_IN_SECONDS), false)
        relayer.publishJsonRpcResponse(
            responseTopic, irnParams, response, envelopeType = EnvelopeType.ONE, participants = Participants(senderPublicKey, receiverPublicKey),
            onSuccess = { Logger.log("Success Responded on topic: $responseTopic") },
            onFailure = { Logger.error("Error Responded on topic: $responseTopic") }
        )
    }

    internal fun getPendingRequests(): List<PendingRequest> {
        if (issuer == null) {
            throw MissingIssuerException
        }
        return getPendingJsonRpcHistoryEntriesUseCase()
            .map { jsonRpcHistoryEntry -> jsonRpcHistoryEntry.toPendingRequest(issuer) }
    }

    private fun onAuthRequest(wcRequest: WCRequest, authParams: AuthParams.RequestParams) {
        if (issuer != null) {
            scope.launch {
                val formattedMessage: String = authParams.payloadParams.toFormattedMessage(issuer)
                _engineEvent.emit(Events.OnAuthRequest(wcRequest.id, formattedMessage))
            }
        } else {
            val irnParams = IrnParams(Tags.AUTH_REQUEST_RESPONSE, Ttl(DAY_IN_SECONDS), false)
            relayer.respondWithError(wcRequest, PeerError.MissingIssuer, irnParams)
        }
    }

    private fun onAuthRequestResponse(wcResponse: WCResponse, requestParams: AuthParams.RequestParams) {
        val pairingTopic = wcResponse.topic

        pairingInterface.updateExpiry(pairingTopic.value, Expiry(MONTH_IN_SECONDS))
        pairingInterface.updateMetadata(pairingTopic.value, requestParams.requester.metadata.toClient(), AppMetaDataType.PEER)
        pairingInterface.activate(pairingTopic.value)

        if (!pairingInterface.getPairings().any { pairing -> pairing.topic == pairingTopic.value }) return

        pairingTopicToResponseTopicMap.remove(pairingTopic)

        when (val response = wcResponse.response) {
            is JsonRpcResponse.JsonRpcError -> {
                scope.launch {
                    _engineEvent.emit(Events.OnAuthResponse(response.id, AuthResponse.Error(response.error.code, response.error.message)))
                }
            }
            is JsonRpcResponse.JsonRpcResult -> {
                val (header, payload, signature) = (response.result as AuthParams.ResponseParams)
                val cacao = Cacao(header, payload, signature)
                if (cacaoVerifier.verify(cacao)) {
                    scope.launch {
                        _engineEvent.emit(Events.OnAuthResponse(response.id, AuthResponse.Result(cacao)))
                    }
                } else {
                    scope.launch {
                        _engineEvent.emit(
                            Events.OnAuthResponse(
                                response.id,
                                AuthResponse.Error(PeerError.SignatureVerificationFailed.code, PeerError.SignatureVerificationFailed.message)
                            )
                        )
                    }
                }
            }
        }
    }

    private fun collectJsonRpcRequests() {
        relayer.clientSyncJsonRpc
            .filter { request -> request.params is AuthParams.RequestParams }
            .onEach { request -> onAuthRequest(request, request.params as AuthParams.RequestParams) }
            .launchIn(scope)
    }

    private fun collectJsonRpcResponses() {
        relayer.peerResponse
            .filter { response -> response.params is AuthParams.RequestParams }
            .onEach { response -> onAuthRequestResponse(response, response.params as AuthParams.RequestParams) }
            .launchIn(scope)
    }

    private fun resubscribeToSequences() {
        relayer.isConnectionAvailable
            .onEach { isAvailable -> _engineEvent.emit(ConnectionState(isAvailable)) }
            .filter { isAvailable: Boolean -> isAvailable }
            .onEach {
                coroutineScope {
                    launch(Dispatchers.IO) { resubscribeToPendingRequestsTopics() }
                }
            }
            .launchIn(scope)
    }

    private fun resubscribeToPendingRequestsTopics() {
        pairingTopicToResponseTopicMap
            .map { it.value }
            .onEach { responseTopic: Topic ->
                relayer.subscribe(responseTopic)
            }
    }

    private fun collectInternalErrors() {
        relayer.internalErrors
            .onEach { exception -> _engineEvent.emit(SDKError(exception)) }
            .launchIn(scope)
    }
}