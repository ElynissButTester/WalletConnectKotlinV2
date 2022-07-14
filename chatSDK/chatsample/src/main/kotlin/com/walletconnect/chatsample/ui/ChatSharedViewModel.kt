package com.walletconnect.chatsample.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walletconnect.chat.client.Chat
import com.walletconnect.chat.client.ChatClient
import com.walletconnect.chatsample.ChatUI
import com.walletconnect.chatsample.R
import com.walletconnect.chatsample.domain.ChatDelegate
import com.walletconnect.chatsample.tag
import com.walletconnect.chatsample.ui.messages.MessageUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking

class ChatSharedViewModel : ViewModel() {
    var currentInvite: Chat.Model.Invite? = null
    var whoWasInvitedENS: String? = null
    val userNameToTopicMap: MutableMap<String, String> = mutableMapOf()

    val listOfInvites: MutableList<ChatUI> = mutableListOf()
    val _listOfInvitesStateFlow: MutableStateFlow<List<ChatUI>> = MutableStateFlow(listOfInvites.toList())
    val listOfInvitesStateFlow: StateFlow<List<ChatUI>> = _listOfInvitesStateFlow

    val listOfThreads: MutableList<ChatUI> = mutableListOf()
    val _listOfThreadsStateFlow: MutableStateFlow<List<ChatUI>> = MutableStateFlow(listOfThreads.toList())
    val listOfThreadsStateFlow: StateFlow<List<ChatUI>> = _listOfThreadsStateFlow

    val listOfMessages: MutableList<MessageUI> = mutableListOf()
    val _listOfMessagesStateFlow: MutableStateFlow<List<MessageUI>> = MutableStateFlow(listOfMessages.toList())
    val listOfMessagesStateFlow: StateFlow<List<MessageUI>> = _listOfMessagesStateFlow

    fun getFirstMessage(peername: String) = listOfMessages.first { it.peerName == peername }
    fun getLastMessage(peername: String) = listOfMessages.last { it.peerName == peername }


    val emittedEvents: Flow<ChatSampleEvents> = ChatDelegate.wcEventModels.map { walletEvent: Chat.Model.Events ->
        Log.d(tag(this), walletEvent.toString())
        when (walletEvent) {
            is Chat.Model.Events.OnInvite -> walletEvent.toChatSampleEvent()
            is Chat.Model.Events.OnJoined -> walletEvent.toChatSampleEvent()
            is Chat.Model.Events.OnMessage -> walletEvent.toChatSampleEvent()
            is Chat.Model.Events.OnLeft -> walletEvent.toChatSampleEvent()
            else -> ChatSampleEvents.NoAction
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed())


    fun onInvite(event: ChatSampleEvents.OnInvite) {
        accountIdToEnsMap[event.invite.account.value]?.let { ens ->
            currentInvite = event.invite

            listOfInvites.add(ChatUI(ensToIconMap[ens]!!, ens, "Gm, my man!", event.id))
            _listOfInvitesStateFlow.update { listOfInvites.toList() }

//            listOfMessages.add(MessageUI(ens, "gm, my man!", System.currentTimeMillis(), ens))
//            _listOfMessagesStateFlow.value = listOfMessages.toList()
        } ?: Log.e(tag(this), "Unable to find accountId: ${event.invite.account.value}")
    }

    fun onJoined(event: ChatSampleEvents.OnJoined) {
        if (currentInvite != null) {
            accountIdToEnsMap[currentInvite?.account?.value]?.let { ens ->
                if (ens != SELF_ENS) {
                    Log.e(tag(this), "ens != SELF_ENS")

                    listOfThreads.add(ChatUI(ensToIconMap[ens]!!, ens, currentInvite!!.message, null))
                    _listOfThreadsStateFlow.value = listOfThreads.toList()

                    userNameToTopicMap.put(ens, event.topic)
                } else {
                    Log.e(tag(this), "ens == SELF_ENS")
                    listOfThreads.add(ChatUI(ensToIconMap[whoWasInvitedENS]!!, whoWasInvitedENS!!, currentInvite!!.message, null))
                    _listOfThreadsStateFlow.value = listOfThreads.toList()
                    userNameToTopicMap.put(whoWasInvitedENS!!, event.topic)
                }
            }
        } else Log.e(tag(this), "Unable to find currentInvite: $event")
    }

    fun onMessage(event: ChatSampleEvents.OnMessage) {
        userNameToTopicMap.entries.find { it.value == event.topic }?.let { (ens, topic) ->
            listOfThreads.find { topic == event.topic }?.let {
                accountIdToEnsMap[event.message.authorAccount.value]?.let { authorEns ->
                    listOfMessages.add(MessageUI(authorEns, event.message.message, event.message.timestamp, authorEns))
                    _listOfMessagesStateFlow.value = listOfMessages.toList()
                }
            } ?: Log.e(tag(this), "Unable to find topic: ${event.topic}")
        }
    }

    fun acceptRequest(chatUI: ChatUI) {
        chatUI.id?.let { id ->
            ChatClient.accept(Chat.Params.Accept(id)) { error ->
                Log.e(tag(this), error.throwable.stackTraceToString())
            }

            listOfInvites.removeIf { it.username == chatUI.username }
            _listOfInvitesStateFlow.value = listOfInvites.toList()
        } ?: Log.e(tag(this), "Unable to find id: $chatUI")
    }

    fun register(listener: Chat.Listeners.Register) {
        ChatClient.register(Chat.Params.Register(Chat.Model.AccountId(SELF_ACCOUNT)), listener)
    }

    fun invite(contact: String, openingMessage: String, afterInviteSent: () -> Unit) {
        ensToAccountIdMap[contact]?.let { accountId ->
            ChatClient.resolve(Chat.Params.Resolve(Chat.Model.AccountId(accountId)), object : Chat.Listeners.Resolve {
                override fun onError(error: Chat.Model.Error) {
                    Log.e(tag(this), error.throwable.stackTraceToString())
                }

                override fun onSuccess(publicKey: String) {
                    ChatClient.addContact(Chat.Params.AddContact(Chat.Model.AccountId(accountId), publicKey)) { error ->
                        Log.e(tag(this), error.throwable.stackTraceToString())
                    }

                    val inviteModel = Chat.Model.Invite(Chat.Model.AccountId(SELF_ACCOUNT), openingMessage)
                    Chat.Params.Invite(Chat.Model.AccountId(accountId), inviteModel).also { invite ->
                        ChatClient.invite(invite) { error -> Log.e(tag(this), error.throwable.stackTraceToString()) }
                    }
                    runBlocking(Dispatchers.Main) {
                        currentInvite = inviteModel
                        whoWasInvitedENS = contact
                        Log.e(tag(this), "invite: $currentInvite")
                        listOfMessages.add(MessageUI(contact, inviteModel.message, System.currentTimeMillis(), SELF_ENS))
                        _listOfMessagesStateFlow.value = listOfMessages.toList()
                        afterInviteSent()
                    }
                }
            })

        } ?: Log.e(tag(this), "Unable to find contact: $contact")
    }

    fun sendMessage(message: String, peername: String) {
        Log.e(tag(this), "sendMessage: $peername")

        val (ens, topic) = userNameToTopicMap.entries.single { it.key == peername }
        listOfMessages.add(MessageUI(ens, message, System.currentTimeMillis(), SELF_ENS))
        _listOfMessagesStateFlow.value = listOfMessages.toList()

        if (ensToAccountIdMap[ens] != null) {
            ChatClient.message(Chat.Params.Message(topic, Chat.Model.AccountId(ensToAccountIdMap[SELF_ENS]!!), message)) { error ->
                runBlocking(Dispatchers.Main) {
                    Log.e(tag(this), error.throwable.stackTraceToString())
                }
            }
        } else {
            Log.e(tag(this), "Unable to find contact: $ens")
        }
    }

    companion object {
        const val SWIFT_ENS = "Swift.eth"
        const val SWIFT_ACCOUNT_ID = "eip155:1:0xab16a96d359ec26a11e2c2b3d8f8b8942d5bfcdb"
        const val KOTLIN_ENS = "Kotlin.eth"
        const val KOTLIN_ACCOUNT_ID = "eip155:2:0xab16a96d359ec26a11e2c2b3d8f8b8942d5bfcdb"
        const val JS_ENS = "Js.eth"
        const val JS_ACCOUNT_ID = "eip155:3:0xab16a96d359ec26a11e2c2b3d8f8b8942d5bfcdb"


        const val TEST_ENS = "mom.eth"
        const val TEST_ACCOUNT_ID = "eip155:4:0xab16a96d359ec26a11e2c2b3d8f8b8942d5bfcdb"


        private const val isPrimary = true
        val SELF_ACCOUNT = if (isPrimary) KOTLIN_ACCOUNT_ID else TEST_ACCOUNT_ID
        val SELF_ENS = if (isPrimary) KOTLIN_ENS else TEST_ENS
//        val SELF_ACCOUNT = JS_ACCOUNT_ID
//        val SELF_ENS = JS_ENS

        val ensToAccountIdMap = mapOf(
            SWIFT_ENS to SWIFT_ACCOUNT_ID,
            KOTLIN_ENS to KOTLIN_ACCOUNT_ID,
            JS_ENS to JS_ACCOUNT_ID,
            TEST_ENS to TEST_ACCOUNT_ID
        )

        val accountIdToEnsMap = ensToAccountIdMap.map { it.value to it.key }.toMap()

        val ensToIconMap = mapOf(
            SWIFT_ENS to R.drawable.ic_chat_icon_2,
            KOTLIN_ENS to R.drawable.ic_chat_icon_1,
            JS_ENS to R.drawable.ic_chat_icon_3,
            TEST_ENS to R.drawable.ic_chat_icon_3
        )
    }
}