package com.mokasas.wikall

import android.os.Handler
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ChatState(
    val available: Boolean = false,
    val sending: Boolean = false,
    val notice: String = "",
    val items: List<ChatNote> = emptyList(),
)

/** Called on the main thread. Crypto and Room access run on the session's serial worker. */
internal class ChatSessionManager(private val store: CallStore, private val main: Handler, private val worker: Executor) {
    private class Account(val realm: String, val number: String, val transmit: (Signal) -> Unit) {
        val key = "$realm:$number"
    }
    private class Draft(val address: ChatAddress, val body: String)

    private var account: Account? = null
    private var draft: Draft? = null
    private var timeout: Runnable? = null
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()

    fun connect(realm: String, number: String, ready: Boolean, transmit: (Signal) -> Unit) {
        pause()
        val next = Account(realm, number, transmit)
        if (account?.key != next.key) mutable.value = ChatState()
        account = next
        mutable.value = mutable.value.copy(available = ready, notice = "")
        updateItems(next)
    }

    fun pause() {
        clearDraft()
        mutable.value = mutable.value.copy(available = false)
    }

    fun detach() {
        pause()
        account = null
        mutable.value = ChatState()
    }

    fun send(number: String, text: String): Boolean {
        val session = account ?: return false
        val body = text.trim()
        if (!mutable.value.available || draft != null || !validNumber(number) || number == session.number ||
            body.isBlank() || body.encodeToByteArray().size > CHAT_TEXT_BYTES) return false
        val message = Draft(ChatAddress(session.realm, session.number, number, UUID.randomUUID()), body)
        draft = message
        mutable.value = mutable.value.copy(sending = true, notice = "")
        timeout = Runnable {
            if (draft === message) finish("انتهت مهلة تأكيد الرسالة.")
        }.also { main.postDelayed(it, 20_000) }
        session.transmit(Signal(SignalType.CHAT_KEY_REQUEST, message.address.id, number = number))
        return true
    }

    fun receive(signal: Signal) {
        val session = account ?: return
        when (signal.type) {
            SignalType.CHAT_READY -> mutable.value = mutable.value.copy(available = true)
            SignalType.CHAT_KEY -> prepare(session, signal)
            SignalType.CHAT_DELIVERY -> saveIncoming(session, signal)
            SignalType.CHAT_ACCEPTED -> {
                val sent = draft?.takeIf { it.address.id == signal.id } ?: return
                finish("حُفظت الرسالة لدى الدليل المحلي.")
                worker.execute {
                    runCatching { store.acceptChat(session.key, sent.address.to, signal.id.toString()) }
                    updateItems(session)
                }
            }
            SignalType.CHAT_DECLINED -> if (draft?.address?.id == signal.id) finish("تعذّر إرسال الرسالة إلى هذا الرقم.")
            else -> Unit
        }
    }

    private fun prepare(session: Account, signal: Signal) {
        val pending = draft?.takeIf { it.address.id == signal.id && it.address.to == signal.number } ?: return
        worker.execute {
            val payload = runCatching {
                val identity = DeviceIdentity.load()
                val cipher = ChatEnvelope.seal(pending.address, pending.body, publicKey(checkNotNull(signal.payload)), identity.signing)
                store.recordChat(ChatNote(session.key, pending.address.to, true, signal.id.toString(), pending.body,
                    System.currentTimeMillis(), false))
                cipher
            }.getOrNull()
            main.post {
                if (account !== session || draft !== pending) return@post
                if (payload == null) finish("تعذّر تشفير الرسالة أو حفظها على الهاتف.")
                else session.transmit(Signal(SignalType.CHAT_SEND, signal.id, payload, pending.address.to))
            }
            updateItems(session)
        }
    }

    private fun saveIncoming(session: Account, signal: Signal) {
        val from = signal.number ?: return
        worker.execute {
            val saved = runCatching {
                val payload = checkNotNull(signal.payload)
                val address = ChatAddress(session.realm, from, session.number, signal.id)
                val body = ChatEnvelope.open(address, payload.substringAfter('.'), publicKey(payload.substringBefore('.')),
                    DeviceIdentity.load().decryption)
                store.recordChat(ChatNote(session.key, from, false, signal.id.toString(), body, System.currentTimeMillis(), true))
            }.isSuccess
            if (saved) {
                main.post {
                    if (account === session) session.transmit(Signal(SignalType.CHAT_ACK, signal.id, number = from))
                }
                updateItems(session)
            }
        }
    }

    private fun updateItems(session: Account) {
        worker.execute {
            val items = runCatching { store.recentChat(session.key) }.getOrNull() ?: return@execute
            main.post { if (account === session) mutable.value = mutable.value.copy(items = items) }
        }
    }

    private fun clearDraft() {
        timeout?.let(main::removeCallbacks)
        timeout = null
        draft = null
        mutable.value = mutable.value.copy(sending = false)
    }

    private fun finish(notice: String) {
        clearDraft()
        mutable.value = mutable.value.copy(notice = notice)
    }
}
