@file:JvmSynthetic

package com.walletconnect.sign.engine.domain

import android.database.sqlite.SQLiteException
import com.walletconnect.android.Core
import com.walletconnect.android.impl.common.SDKError
import com.walletconnect.android.impl.common.model.ConnectionState
import com.walletconnect.android.impl.common.model.type.EngineEvent
import com.walletconnect.android.impl.utils.*
import com.walletconnect.android.internal.common.JsonRpcResponse
import com.walletconnect.android.internal.common.crypto.KeyManagementRepository
import com.walletconnect.android.internal.common.exception.GenericException
import com.walletconnect.android.internal.common.exception.Reason
import com.walletconnect.android.internal.common.exception.Uncategorized
import com.walletconnect.android.internal.common.exception.WalletConnectException
import com.walletconnect.android.internal.common.model.*
import com.walletconnect.android.internal.common.scope
import com.walletconnect.android.internal.common.storage.MetadataStorageRepositoryInterface
import com.walletconnect.android.pairing.PairingInterface
import com.walletconnect.android.pairing.toClient
import com.walletconnect.android.pairing.toPairing
import com.walletconnect.foundation.common.model.PublicKey
import com.walletconnect.foundation.common.model.Topic
import com.walletconnect.foundation.common.model.Ttl
import com.walletconnect.sign.common.exceptions.*
import com.walletconnect.sign.common.model.PendingRequest
import com.walletconnect.sign.common.model.type.Sequences
import com.walletconnect.sign.common.model.vo.clientsync.common.NamespaceVO
import com.walletconnect.sign.common.model.vo.clientsync.common.SessionParticipantVO
import com.walletconnect.sign.common.model.vo.clientsync.session.SignRpcVO
import com.walletconnect.sign.common.model.vo.clientsync.session.params.SignParamsVO
import com.walletconnect.sign.common.model.vo.clientsync.session.payload.SessionEventVO
import com.walletconnect.sign.common.model.vo.clientsync.session.payload.SessionRequestVO
import com.walletconnect.sign.common.model.vo.sequence.SessionVO
import com.walletconnect.sign.engine.model.EngineDO
import com.walletconnect.sign.engine.model.mapper.*
import com.walletconnect.sign.json_rpc.domain.GetPendingRequestsUseCase
import com.walletconnect.sign.json_rpc.model.JsonRpcMethod
import com.walletconnect.sign.storage.sequence.SessionStorageRepository
import com.walletconnect.util.generateId
import com.walletconnect.utils.Empty
import com.walletconnect.utils.extractTimestamp
import com.walletconnect.utils.isSequenceValid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class SignEngine(
    private val relayer: JsonRpcInteractorInterface,
    private val getPendingRequestsUseCase: GetPendingRequestsUseCase,
    private val crypto: KeyManagementRepository,
    private val sessionStorageRepository: SessionStorageRepository,
    private val metadataStorageRepository: MetadataStorageRepositoryInterface,
    private val pairingInterface: PairingInterface,
    private val selfAppMetaData: AppMetaData,
) {
    private val _engineEvent: MutableSharedFlow<EngineEvent> = MutableSharedFlow()
    val engineEvent: SharedFlow<EngineEvent> = _engineEvent.asSharedFlow()
    private val sessionProposalRequest: MutableMap<String, WCRequest> = mutableMapOf()

    init {
        resubscribeToSequences()
        setupSequenceExpiration()
        collectJsonRpcRequests()
        collectJsonRpcResponses()
        collectInternalErrors()

        pairingInterface.register(
            JsonRpcMethod.WC_SESSION_PROPOSE,
            JsonRpcMethod.WC_SESSION_SETTLE,
            JsonRpcMethod.WC_SESSION_REQUEST,
            JsonRpcMethod.WC_SESSION_EVENT,
            JsonRpcMethod.WC_SESSION_DELETE,
            JsonRpcMethod.WC_SESSION_EXTEND,
            JsonRpcMethod.WC_SESSION_PING,
            JsonRpcMethod.WC_SESSION_UPDATE
        )
    }

    fun handleInitializationErrors(onError: (WalletConnectException) -> Unit) {
        relayer.initializationErrorsFlow.onEach { walletConnectException ->
            onError(walletConnectException)
        }.launchIn(scope)
    }

    private fun resubscribeToSequences() {
        relayer.isConnectionAvailable
            .onEach { isAvailable -> _engineEvent.emit(ConnectionState(isAvailable)) }
            .filter { isAvailable: Boolean -> isAvailable }
            .onEach {
                coroutineScope {
                    launch(Dispatchers.IO) { resubscribeToSession() }
                }
            }
            .launchIn(scope)
    }

    internal fun proposeSession(
        namespaces: Map<String, EngineDO.Namespace.Proposal>,
        pairing: Pairing,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val relay = RelayProtocolOptions(pairing.relayProtocol, pairing.relayData)

        Validator.validateProposalNamespace(namespaces.toNamespacesVOProposal()) { error ->
            throw InvalidNamespaceException(error.message)
        }

        val selfPublicKey: PublicKey = crypto.generateKeyPair()
        val sessionProposal: SignParamsVO.SessionProposeParams = toSessionProposeParams(listOf(relay), namespaces, selfPublicKey, selfAppMetaData)
        val request = SignRpcVO.SessionPropose(id = generateId(), params = sessionProposal)
        sessionProposalRequest[selfPublicKey.keyAsHex] = WCRequest(pairing.topic, request.id, request.method, sessionProposal)
        val irnParams = IrnParams(Tags.SESSION_PROPOSE, Ttl(FIVE_MINUTES_IN_SECONDS), true)
        relayer.subscribe(pairing.topic)

        relayer.publishJsonRpcRequests(pairing.topic, irnParams, request,
            onSuccess = {
                Logger.log("Session proposal sent successfully")
                onSuccess()
            },
            onFailure = { error ->
                Logger.error("Failed to send a session proposal: $error")
                onFailure(error)
            })
    }

    internal fun pair(uri: String) {
        pairingInterface.pair(Core.Params.Pair(uri)) {}
    }

    internal fun reject(proposerPublicKey: String, reason: String, onFailure: (Throwable) -> Unit = {}) {
        val request = sessionProposalRequest[proposerPublicKey]
            ?: throw CannotFindSessionProposalException("$NO_SESSION_PROPOSAL$proposerPublicKey")
        sessionProposalRequest.remove(proposerPublicKey)
        val irnParams = IrnParams(Tags.SESSION_PROPOSE_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))

        relayer.respondWithError(request, PeerError.EIP1193.UserRejectedRequest(reason), irnParams, onFailure = { error -> onFailure(error) })
    }

    internal fun approve(
        proposerPublicKey: String,
        namespaces: Map<String, EngineDO.Namespace.Session>,
        onFailure: (Throwable) -> Unit = {},
    ) {
        fun sessionSettle(
            requestId: Long,
            proposal: SignParamsVO.SessionProposeParams,
            sessionTopic: Topic,
            pairingTopic: Topic,
        ) {
            val (selfPublicKey, _) = crypto.getKeyAgreement(sessionTopic)
            val selfParticipant = SessionParticipantVO(selfPublicKey.keyAsHex, selfAppMetaData)
            val sessionExpiry = ACTIVE_SESSION
            val unacknowledgedSession = SessionVO.createUnacknowledgedSession(sessionTopic, proposal, selfParticipant, sessionExpiry, namespaces)

            try {
                sessionStorageRepository.insertSession(unacknowledgedSession, pairingTopic, requestId)
                metadataStorageRepository.insertOrAbortMetadata(sessionTopic, selfAppMetaData, AppMetaDataType.SELF)
                metadataStorageRepository.insertOrAbortMetadata(sessionTopic, proposal.proposer.metadata, AppMetaDataType.PEER)
                val params = proposal.toSessionSettleParams(selfParticipant, sessionExpiry, namespaces)
                val sessionSettle = SignRpcVO.SessionSettle(id = generateId(), params = params)
                val irnParams = IrnParams(Tags.SESSION_SETTLE, Ttl(FIVE_MINUTES_IN_SECONDS))

                relayer.publishJsonRpcRequests(sessionTopic, irnParams, sessionSettle, onFailure = { error -> onFailure(error) })
            } catch (e: SQLiteException) {
                sessionStorageRepository.deleteSession(sessionTopic)
                // todo: missing metadata deletion. Also check other try catches
                onFailure(e)
            }
        }

        val request = sessionProposalRequest[proposerPublicKey] ?: throw CannotFindSessionProposalException("$NO_SESSION_PROPOSAL$proposerPublicKey")
        sessionProposalRequest.remove(proposerPublicKey)
        val proposal = request.params as SignParamsVO.SessionProposeParams

        Validator.validateSessionNamespace(namespaces.toMapOfNamespacesVOSession(), proposal.namespaces) { error ->
            throw InvalidNamespaceException(error.message)
        }

        val selfPublicKey: PublicKey = crypto.generateKeyPair()
        val sessionTopic = crypto.generateTopicFromKeyAgreement(selfPublicKey, PublicKey(proposerPublicKey))
        val approvalParams = proposal.toSessionApproveParams(selfPublicKey)
        val irnParams = IrnParams(Tags.SESSION_PROPOSE_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))

        relayer.subscribe(sessionTopic)
        relayer.respondWithParams(request, approvalParams, irnParams)

        sessionSettle(request.id, proposal, sessionTopic, request.topic)
    }

    internal fun sessionUpdate(
        topic: String,
        namespaces: Map<String, EngineDO.Namespace.Session>,
        onFailure: (Throwable) -> Unit,
    ) {
        if (!sessionStorageRepository.isSessionValid(Topic(topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
        }

        val session = sessionStorageRepository.getSessionWithoutMetadataByTopic(Topic(topic))

        if (!session.isSelfController) {
            throw UnauthorizedPeerException(UNAUTHORIZED_UPDATE_MESSAGE)
        }

        if (!session.isAcknowledged) {
            throw NotSettledSessionException("$SESSION_IS_NOT_ACKNOWLEDGED_MESSAGE$topic")
        }

        Validator.validateSessionNamespace(namespaces.toMapOfNamespacesVOSession(), session.proposalNamespaces) { error ->
            throw InvalidNamespaceException(error.message)
        }

        val params = SignParamsVO.UpdateNamespacesParams(namespaces.toMapOfNamespacesVOSession())
        val sessionUpdate = SignRpcVO.SessionUpdate(id = generateId(), params = params)
        val irnParams = IrnParams(Tags.SESSION_UPDATE, Ttl(DAY_IN_SECONDS))

        sessionStorageRepository.insertTempNamespaces(topic, namespaces.toMapOfNamespacesVOSession(), sessionUpdate.id,
            onSuccess = {
                relayer.publishJsonRpcRequests(Topic(topic), irnParams, sessionUpdate,
                    onSuccess = { Logger.log("Update sent successfully") },
                    onFailure = { error ->
                        Logger.error("Sending session update error: $error")
                        sessionStorageRepository.deleteTempNamespacesByRequestId(sessionUpdate.id)
                        onFailure(error)
                    }
                )
            }, onFailure = {
                onFailure(GenericException("Error updating namespaces"))
            })
    }

    internal fun sessionRequest(request: EngineDO.Request, onFailure: (Throwable) -> Unit) {
        if (!sessionStorageRepository.isSessionValid(Topic(request.topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE${request.topic}")
        }

        Validator.validateSessionRequest(request) { error ->
            throw InvalidRequestException(error.message)
        }

        val namespaces: Map<String, NamespaceVO.Session> = sessionStorageRepository.getSessionWithoutMetadataByTopic(Topic(request.topic)).namespaces
        Validator.validateChainIdWithMethodAuthorisation(request.chainId, request.method, namespaces) { error ->
            throw UnauthorizedMethodException(error.message)
        }

        val params = SignParamsVO.SessionRequestParams(SessionRequestVO(request.method, request.params), request.chainId)
        val sessionPayload = SignRpcVO.SessionRequest(id = generateId(), params = params)
        val irnParams = IrnParams(Tags.SESSION_REQUEST, Ttl(FIVE_MINUTES_IN_SECONDS), true)

        relayer.publishJsonRpcRequests(
            Topic(request.topic),
            irnParams,
            sessionPayload,
            onSuccess = {
                Logger.log("Session request sent successfully")
                scope.launch {
                    try {
                        withTimeout(FIVE_MINUTES_TIMEOUT) {
                            collectResponse(sessionPayload.id) { cancel() }
                        }
                    } catch (e: TimeoutCancellationException) {
                        onFailure(e)
                    }
                }
            },
            onFailure = { error ->
                Logger.error("Sending session request error: $error")
                onFailure(error)
            }
        )
    }

    internal fun respondSessionRequest(
        topic: String,
        jsonRpcResponse: JsonRpcResponse,
        onFailure: (Throwable) -> Unit,
    ) {
        if (!sessionStorageRepository.isSessionValid(Topic(topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
        }
        val irnParams = IrnParams(Tags.SESSION_REQUEST_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))

        relayer.publishJsonRpcResponse(Topic(topic), irnParams, jsonRpcResponse,
            { Logger.log("Session payload sent successfully") },
            { error ->
                Logger.error("Sending session payload response error: $error")
                onFailure(error)
            })
    }

    // TODO: Do we still want Session Ping
    internal fun ping(topic: String, onSuccess: (String) -> Unit, onFailure: (Throwable) -> Unit) {
        if (sessionStorageRepository.isSessionValid(Topic(topic))) {
            val pingPayload = SignRpcVO.SessionPing(id = generateId(), params = SignParamsVO.PingParams())
            val irnParams = IrnParams(Tags.SESSION_PING, Ttl(THIRTY_SECONDS))

            relayer.publishJsonRpcRequests(Topic(topic), irnParams, pingPayload,
                onSuccess = {
                    Logger.log("Ping sent successfully")
                    scope.launch {
                        try {
                            withTimeout(THIRTY_SECONDS_TIMEOUT) {
                                collectResponse(pingPayload.id) { result ->
                                    cancel()
                                    result.fold(
                                        onSuccess = {
                                            Logger.log("Ping peer response success")
                                            onSuccess(topic)
                                        },
                                        onFailure = { error ->

                                            Logger.log("Ping peer response error: $error")

                                            onFailure(error)
                                        })
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            onFailure(e)
                        }
                    }
                },
                onFailure = { error ->
                    Logger.log("Ping sent error: $error")
                    onFailure(error)
                })
        } else {
            pairingInterface.ping(Core.Params.Ping(topic), object : Core.Listeners.PairingPing {
                override fun onSuccess(pingSuccess: Core.Model.Ping.Success) {
                    onSuccess(pingSuccess.topic)
                }

                override fun onError(pingError: Core.Model.Ping.Error) {
                    onFailure(pingError.error)
                }
            })
        }
    }

    internal fun emit(topic: String, event: EngineDO.Event, onFailure: (Throwable) -> Unit) {
        if (!sessionStorageRepository.isSessionValid(Topic(topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
        }

        val session = sessionStorageRepository.getSessionWithoutMetadataByTopic(Topic(topic))
        if (!session.isSelfController) {
            throw UnauthorizedPeerException(UNAUTHORIZED_EMIT_MESSAGE)
        }

        Validator.validateEvent(event) { error ->
            throw InvalidEventException(error.message)
        }

        val namespaces = session.namespaces
        Validator.validateChainIdWithEventAuthorisation(event.chainId, event.name, namespaces) { error ->
            throw UnauthorizedEventException(error.message)
        }

        val eventParams = SignParamsVO.EventParams(SessionEventVO(event.name, event.data), event.chainId)
        val sessionEvent = SignRpcVO.SessionEvent(id = generateId(), params = eventParams)
        val irnParams = IrnParams(Tags.SESSION_EVENT, Ttl(FIVE_MINUTES_IN_SECONDS), true)

        relayer.publishJsonRpcRequests(Topic(topic), irnParams, sessionEvent,
            onSuccess = { Logger.log("Event sent successfully") },
            onFailure = { error ->
                Logger.error("Sending event error: $error")
                onFailure(error)
            }
        )
    }

    internal fun extend(topic: String, onFailure: (Throwable) -> Unit) {
        if (!sessionStorageRepository.isSessionValid(Topic(topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
        }

        val session = sessionStorageRepository.getSessionWithoutMetadataByTopic(Topic(topic))
        if (!session.isSelfController) {
            throw UnauthorizedPeerException(UNAUTHORIZED_EXTEND_MESSAGE)
        }
        if (!session.isAcknowledged) {
            throw NotSettledSessionException("$SESSION_IS_NOT_ACKNOWLEDGED_MESSAGE$topic")
        }

        val newExpiration = session.expiry.seconds + WEEK_IN_SECONDS
        sessionStorageRepository.extendSession(Topic(topic), newExpiration)
        val sessionExtend = SignRpcVO.SessionExtend(id = generateId(), params = SignParamsVO.ExtendParams(newExpiration))
        val irnParams = IrnParams(Tags.SESSION_EXTEND, Ttl(DAY_IN_SECONDS))

        relayer.publishJsonRpcRequests(Topic(topic), irnParams, sessionExtend,
            onSuccess = { Logger.log("Session extend sent successfully") },
            onFailure = { error ->
                Logger.error("Sending session extend error: $error")
                onFailure(error)
            })
    }

    internal fun disconnect(topic: String) {
        if (!sessionStorageRepository.isSessionValid(Topic(topic))) {
            throw CannotFindSequenceForTopic("$NO_SEQUENCE_FOR_TOPIC_MESSAGE$topic")
        }

        val deleteParams = SignParamsVO.DeleteParams(Reason.UserDisconnected.code, Reason.UserDisconnected.message)
        val sessionDelete = SignRpcVO.SessionDelete(id = generateId(), params = deleteParams)
        sessionStorageRepository.deleteSession(Topic(topic))
        relayer.unsubscribe(Topic(topic))
        val irnParams = IrnParams(Tags.SESSION_DELETE, Ttl(DAY_IN_SECONDS))

        relayer.publishJsonRpcRequests(Topic(topic), irnParams, sessionDelete,
            onSuccess = { Logger.error("Disconnect sent successfully") },
            onFailure = { error -> Logger.error("Sending session disconnect error: $error") }
        )
    }

    internal fun getListOfSettledSessions(): List<EngineDO.Session> {
        return sessionStorageRepository.getListOfSessionVOsWithoutMetadata()
            .filter { session -> session.isAcknowledged && session.expiry.isSequenceValid() }
            .map { session ->
                val peerMetaData = metadataStorageRepository.getByTopicAndType(session.topic, AppMetaDataType.PEER)
                session.copy(selfAppMetaData = selfAppMetaData, peerAppMetaData = peerMetaData)
            }
            .map { session -> session.toEngineDO() }
    }

    internal fun getListOfSettledPairings(): List<EngineDO.PairingSettle> {
        return pairingInterface.getPairings().map { pairing ->
            val mappedPairing = pairing.toPairing()
            EngineDO.PairingSettle(mappedPairing.topic, mappedPairing.peerAppMetaData)
        }
    }

    internal fun getPendingRequests(topic: Topic): List<PendingRequest> = getPendingRequestsUseCase(topic)

    private suspend fun collectResponse(id: Long, onResponse: (Result<JsonRpcResponse.JsonRpcResult>) -> Unit = {}) {
        relayer.peerResponse
            .filter { response -> response.response.id == id }
            .collect { response ->
                when (val result = response.response) {
                    is JsonRpcResponse.JsonRpcResult -> onResponse(Result.success(result))
                    is JsonRpcResponse.JsonRpcError -> onResponse(Result.failure(Throwable(result.errorMessage)))
                }
            }
    }

    private fun collectJsonRpcRequests() {
        relayer.clientSyncJsonRpc
            .filter { request -> request.params is SignParamsVO }
            .onEach { request ->
                when (val requestParams = request.params) {
                    is SignParamsVO.SessionProposeParams -> onSessionPropose(request, requestParams)
                    is SignParamsVO.SessionSettleParams -> onSessionSettle(request, requestParams)
                    is SignParamsVO.SessionRequestParams -> onSessionRequest(request, requestParams)
                    is SignParamsVO.DeleteParams -> onSessionDelete(request, requestParams)
                    is SignParamsVO.EventParams -> onSessionEvent(request, requestParams)
                    is SignParamsVO.UpdateNamespacesParams -> onSessionUpdate(request, requestParams)
                    is SignParamsVO.ExtendParams -> onSessionExtend(request, requestParams)
                    is SignParamsVO.PingParams -> onPing(request)
                }
            }.launchIn(scope)
    }

    private fun collectInternalErrors() {
        merge(relayer.internalErrors, pairingInterface.findWrongMethodsFlow)
            .onEach { exception -> _engineEvent.emit(SDKError(exception)) }
            .launchIn(scope)
    }

    private fun collectJsonRpcResponses() {
        scope.launch {
            relayer.peerResponse.collect { response ->
                when (val params = response.params) {
                    is SignParamsVO.SessionProposeParams -> onSessionProposalResponse(response, params)
                    is SignParamsVO.SessionSettleParams -> onSessionSettleResponse(response)
                    is SignParamsVO.UpdateNamespacesParams -> onSessionUpdateResponse(response)
                    is SignParamsVO.SessionRequestParams -> onSessionRequestResponse(response, params)
                }
            }
        }
    }

    // listened by WalletDelegate
    private fun onSessionPropose(request: WCRequest, payloadParams: SignParamsVO.SessionProposeParams) {
        Validator.validateProposalNamespace(payloadParams.namespaces) { error ->
            val irnParams = IrnParams(Tags.SESSION_PROPOSE_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        sessionProposalRequest[payloadParams.proposer.publicKey] = request
        pairingInterface.updateMetadata(request.topic.value, payloadParams.proposer.metadata.toClient(), AppMetaDataType.PEER)

        scope.launch { _engineEvent.emit(payloadParams.toEngineDO()) }
    }

    // listened by DappDelegate
    private fun onSessionSettle(request: WCRequest, settleParams: SignParamsVO.SessionSettleParams) {
        val sessionTopic = request.topic
        val (selfPublicKey, _) = crypto.getKeyAgreement(sessionTopic)
        val peerMetadata = settleParams.controller.metadata
        val proposal = sessionProposalRequest[selfPublicKey.keyAsHex] ?: return
        val irnParams = IrnParams(Tags.SESSION_SETTLE, Ttl(FIVE_MINUTES_IN_SECONDS))

        if (proposal.params !is SignParamsVO.SessionProposeParams) {
            relayer.respondWithError(request, PeerError.Failure.SessionSettlementFailed(NAMESPACE_MISSING_PROPOSAL_MESSAGE), irnParams)
            return
        }

        val proposalNamespaces = (proposal.params as SignParamsVO.SessionProposeParams).namespaces

        Validator.validateSessionNamespace(settleParams.namespaces, proposalNamespaces) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        val tempProposalRequest = sessionProposalRequest.getValue(selfPublicKey.keyAsHex)

        try {
            val session = SessionVO.createAcknowledgedSession(sessionTopic, settleParams, selfPublicKey, selfAppMetaData, proposalNamespaces)

            sessionProposalRequest.remove(selfPublicKey.keyAsHex)
            sessionStorageRepository.insertSession(session, request.topic, request.id)
            pairingInterface.updateMetadata(proposal.topic.value, peerMetadata.toClient(), AppMetaDataType.PEER)
            metadataStorageRepository.insertOrAbortMetadata(sessionTopic, peerMetadata, AppMetaDataType.PEER)

            relayer.respondWithSuccess(request, IrnParams(Tags.SESSION_SETTLE, Ttl(FIVE_MINUTES_IN_SECONDS)))
            scope.launch { _engineEvent.emit(session.toSessionApproved()) }
        } catch (e: SQLiteException) {
            sessionProposalRequest[selfPublicKey.keyAsHex] = tempProposalRequest
            sessionStorageRepository.deleteSession(sessionTopic)
            relayer.respondWithError(request, PeerError.Failure.SessionSettlementFailed(e.message ?: String.Empty), irnParams)
            return
        }
    }

    // listened by both Delegates
    private fun onSessionDelete(request: WCRequest, params: SignParamsVO.DeleteParams) {
        if (!sessionStorageRepository.isSessionValid(request.topic)) {
            val irnParams = IrnParams(Tags.SESSION_DELETE_RESPONSE, Ttl(DAY_IN_SECONDS))
            relayer.respondWithError(request, Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        relayer.unsubscribe(request.topic, onSuccess = {
            crypto.removeKeys(request.topic.value)
            sessionStorageRepository.deleteSession(request.topic)
            scope.launch { _engineEvent.emit(params.toEngineDO(request.topic)) }
        }, onFailure = { error ->
            Logger.error(error)
        })
    }

    // listened by WalletDelegate
    private fun onSessionRequest(request: WCRequest, params: SignParamsVO.SessionRequestParams) {
        val irnParams = IrnParams(Tags.SESSION_REQUEST_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))
        Validator.validateSessionRequest(params.toEngineDO(request.topic)) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        if (!sessionStorageRepository.isSessionValid(request.topic)) {
            relayer.respondWithError(request, Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        val (sessionNamespaces: Map<String, NamespaceVO.Session>, sessionPeerAppMetaData: AppMetaData?) = sessionStorageRepository.getSessionWithoutMetadataByTopic(request.topic).run {
            val peerAppMetaData = metadataStorageRepository.getByTopicAndType(this.topic, AppMetaDataType.PEER)
            this.namespaces to peerAppMetaData
        }

        val method = params.request.method
        Validator.validateChainIdWithMethodAuthorisation(params.chainId, method, sessionNamespaces) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        scope.launch { _engineEvent.emit(params.toEngineDO(request, sessionPeerAppMetaData)) }
    }

    // listened by DappDelegate
    private fun onSessionEvent(request: WCRequest, params: SignParamsVO.EventParams) {
        val irnParams = IrnParams(Tags.SESSION_EVENT_RESPONSE, Ttl(FIVE_MINUTES_IN_SECONDS))
        Validator.validateEvent(params.toEngineDOEvent()) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        if (!sessionStorageRepository.isSessionValid(request.topic)) {
            relayer.respondWithError(request, Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        val session = sessionStorageRepository.getSessionWithoutMetadataByTopic(request.topic)
        if (!session.isPeerController) {
            relayer.respondWithError(request, PeerError.Unauthorized.Event(Sequences.SESSION.name), irnParams)
            return
        }
        if (!session.isAcknowledged) {
            relayer.respondWithError(request, Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        val event = params.event
        Validator.validateChainIdWithEventAuthorisation(params.chainId, event.name, session.namespaces) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        relayer.respondWithSuccess(request, irnParams)
        scope.launch { _engineEvent.emit(params.toEngineDO(request.topic)) }
    }

    // listened by DappDelegate
    private fun onSessionUpdate(request: WCRequest, params: SignParamsVO.UpdateNamespacesParams) {
        val irnParams = IrnParams(Tags.SESSION_UPDATE_RESPONSE, Ttl(DAY_IN_SECONDS))
        if (!sessionStorageRepository.isSessionValid(request.topic)) {
            relayer.respondWithError(request, Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        val session: SessionVO = sessionStorageRepository.getSessionWithoutMetadataByTopic(request.topic)
        if (!session.isPeerController) {
            relayer.respondWithError(request, PeerError.Unauthorized.UpdateRequest(Sequences.SESSION.name), irnParams)
            return
        }

        Validator.validateSessionNamespace(params.namespaces, session.proposalNamespaces) { error ->
            relayer.respondWithError(request, PeerError.Invalid.UpdateRequest(error.message), irnParams)
            return
        }

        if (!sessionStorageRepository.isUpdatedNamespaceValid(session.topic.value, request.id.extractTimestamp())) {
            relayer.respondWithError(request, PeerError.Invalid.UpdateRequest("Update Namespace Request ID too old"), irnParams)
            return
        }

        sessionStorageRepository.deleteNamespaceAndInsertNewNamespace(session.topic.value, params.namespaces, request.id, onSuccess = {
            relayer.respondWithSuccess(request, irnParams)

            scope.launch {
                _engineEvent.emit(EngineDO.SessionUpdateNamespaces(request.topic, params.namespaces.toMapOfEngineNamespacesSession()))
            }
        }, onFailure = {
            relayer.respondWithError(
                request,
                PeerError.Invalid.UpdateRequest("Updating Namespace Failed. Review Namespace structure"),
                irnParams
            )
        })
    }

    // listened by DappDelegate
    private fun onSessionExtend(request: WCRequest, requestParams: SignParamsVO.ExtendParams) {
        val irnParams = IrnParams(Tags.SESSION_EXTEND_RESPONSE, Ttl(DAY_IN_SECONDS))
        if (!sessionStorageRepository.isSessionValid(request.topic)) {
            relayer.respondWithError(request, Uncategorized.NoMatchingTopic(Sequences.SESSION.name, request.topic.value), irnParams)
            return
        }

        val session = sessionStorageRepository.getSessionWithoutMetadataByTopic(request.topic)
        if (!session.isPeerController) {
            relayer.respondWithError(request, PeerError.Unauthorized.ExtendRequest(Sequences.SESSION.name), irnParams)
            return
        }

        val newExpiry = requestParams.expiry
        Validator.validateSessionExtend(newExpiry, session.expiry.seconds) { error ->
            relayer.respondWithError(request, error.toPeerError(), irnParams)
            return
        }

        sessionStorageRepository.extendSession(request.topic, newExpiry)
        relayer.respondWithSuccess(request, irnParams)
        scope.launch { _engineEvent.emit(session.toEngineDOSessionExtend(Expiry(newExpiry))) }
    }

    private fun onPing(request: WCRequest) {
        val irnParams = IrnParams(Tags.SESSION_PING_RESPONSE, Ttl(THIRTY_SECONDS))
        relayer.respondWithSuccess(request, irnParams)
    }

    // listened by DappDelegate
    private fun onSessionProposalResponse(wcResponse: WCResponse, params: SignParamsVO.SessionProposeParams) {
        val pairingTopic = wcResponse.topic
        Logger.log("pairingTopic: $pairingTopic")
        pairingInterface.updateExpiry(pairingTopic.value, Expiry(MONTH_IN_SECONDS))
        pairingInterface.activate(pairingTopic.value)

        if (!pairingInterface.getPairings().any { pairing -> pairing.topic == pairingTopic.value }) return

        when (val response = wcResponse.response) {
            is JsonRpcResponse.JsonRpcResult -> {
                Logger.log("Session proposal approve received")
                val selfPublicKey = PublicKey(params.proposer.publicKey)
                val approveParams = response.result as SignParamsVO.ApprovalParams
                val responderPublicKey = PublicKey(approveParams.responderPublicKey)
                val sessionTopic = crypto.generateTopicFromKeyAgreement(selfPublicKey, responderPublicKey)
                relayer.subscribe(sessionTopic)
            }
            is JsonRpcResponse.JsonRpcError -> {
                Logger.log("Session proposal reject received: ${response.error}")
                scope.launch { _engineEvent.emit(EngineDO.SessionRejected(pairingTopic.value, response.errorMessage)) }
            }
        }
    }

    // listened by WalletDelegate
    private fun onSessionSettleResponse(wcResponse: WCResponse) {
        val sessionTopic = wcResponse.topic
        if (!sessionStorageRepository.isSessionValid(sessionTopic)) return
        val session = sessionStorageRepository.getSessionWithoutMetadataByTopic(sessionTopic).run {
            val peerAppMetaData = metadataStorageRepository.getByTopicAndType(this.topic, AppMetaDataType.PEER)
            this.copy(selfAppMetaData = selfAppMetaData, peerAppMetaData = peerAppMetaData)
        }

        when (wcResponse.response) {
            is JsonRpcResponse.JsonRpcResult -> {
                Logger.log("Session settle success received")
                sessionStorageRepository.acknowledgeSession(sessionTopic)
                scope.launch { _engineEvent.emit(EngineDO.SettledSessionResponse.Result(session.toEngineDO())) }
            }
            is JsonRpcResponse.JsonRpcError -> {
                Logger.error("Peer failed to settle session: ${(wcResponse.response as JsonRpcResponse.JsonRpcError).errorMessage}")
                relayer.unsubscribe(sessionTopic, onSuccess = {
                    sessionStorageRepository.deleteSession(sessionTopic)
                    crypto.removeKeys(sessionTopic.value)
                })
            }
        }
    }

    // listened by WalletDelegate
    private fun onSessionUpdateResponse(wcResponse: WCResponse) {
        val sessionTopic = wcResponse.topic
        if (!sessionStorageRepository.isSessionValid(sessionTopic)) return
        val session = sessionStorageRepository.getSessionWithoutMetadataByTopic(sessionTopic)
        if (!sessionStorageRepository.isUpdatedNamespaceResponseValid(session.topic.value, wcResponse.response.id.extractTimestamp())) {
            return
        }

        when (val response = wcResponse.response) {
            is JsonRpcResponse.JsonRpcResult -> {
                Logger.log("Session update namespaces response received")
                val responseId = wcResponse.response.id
                val namespaces = sessionStorageRepository.getTempNamespaces(responseId)

                sessionStorageRepository.deleteNamespaceAndInsertNewNamespace(session.topic.value, namespaces, responseId,
                    onSuccess = {
                        sessionStorageRepository.markUnAckNamespaceAcknowledged(responseId)
                        scope.launch {
                            _engineEvent.emit(
                                EngineDO.SessionUpdateNamespacesResponse.Result(
                                    session.topic,
                                    session.namespaces.toMapOfEngineNamespacesSession()
                                )
                            )
                        }
                    },
                    onFailure = {
                        scope.launch { _engineEvent.emit(EngineDO.SessionUpdateNamespacesResponse.Error("Unable to update the session")) }
                    })
            }
            is JsonRpcResponse.JsonRpcError -> {
                Logger.error("Peer failed to update session namespaces: ${response.error}")
                scope.launch { _engineEvent.emit(EngineDO.SessionUpdateNamespacesResponse.Error(response.errorMessage)) }
            }
        }
    }

    // listened by DappDelegate
    private fun onSessionRequestResponse(response: WCResponse, params: SignParamsVO.SessionRequestParams) {
        val result = when (response.response) {
            is JsonRpcResponse.JsonRpcResult -> (response.response as JsonRpcResponse.JsonRpcResult).toEngineDO()
            is JsonRpcResponse.JsonRpcError -> (response.response as JsonRpcResponse.JsonRpcError).toEngineDO()
        }
        val method = params.request.method
        scope.launch { _engineEvent.emit(EngineDO.SessionPayloadResponse(response.topic.value, params.chainId, method, result)) }
    }

    private fun resubscribeToSession() {
        val (listOfExpiredSession, listOfValidSessions) =
            sessionStorageRepository.getListOfSessionVOsWithoutMetadata().partition { session -> !session.expiry.isSequenceValid() }

        listOfExpiredSession
            .map { session -> session.topic }
            .onEach { sessionTopic ->
                relayer.unsubscribe(sessionTopic, onSuccess = {
                    crypto.removeKeys(sessionTopic.value)
                    sessionStorageRepository.deleteSession(sessionTopic)
                })
            }

        listOfValidSessions
            .onEach { session -> relayer.subscribe(session.topic) }
    }

    private fun setupSequenceExpiration() {
        sessionStorageRepository.onSessionExpired = { sessionTopic ->
            relayer.unsubscribe(sessionTopic, onSuccess = {
                sessionStorageRepository.deleteSession(sessionTopic)
                crypto.removeKeys(sessionTopic.value)
            })
        }

        pairingInterface.topicExpiredFlow.onEach { topic ->
            sessionStorageRepository.getAllSessionTopicsByPairingTopic(topic).onEach { sessionTopic ->
                relayer.unsubscribe(Topic(sessionTopic), onSuccess = {
                    sessionStorageRepository.deleteSession(Topic(sessionTopic))
                    crypto.removeKeys(sessionTopic)
                })
            }
        }.launchIn(scope)
    }

    private companion object {
        const val THIRTY_SECONDS_TIMEOUT: Long = 30000L
        const val FIVE_MINUTES_TIMEOUT: Long = 300000L
    }
}