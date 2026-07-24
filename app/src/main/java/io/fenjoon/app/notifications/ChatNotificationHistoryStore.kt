package io.fenjoon.app.notifications

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small persistent cache used to rebuild a conversation's native MessagingStyle notification.
 * It deliberately stores only recent notification snippets, never the full conversation history.
 */
internal class ChatNotificationHistoryStore private constructor(
    private val storage: Storage,
    private val nowMillis: () -> Long,
    private val maxEntries: Int,
    private val expiryMillis: Long,
) {
    constructor(context: Context) : this(
        storage = SharedPreferencesStorage(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
        nowMillis = System::currentTimeMillis,
        maxEntries = DEFAULT_MAX_MESSAGES,
        expiryMillis = DEFAULT_EXPIRY_MILLIS,
    )

    /** One recent line plus enough thread metadata to rebuild a tappable/actionable card. */
    internal data class ChatNotificationEntry(
        val conversationId: String,
        val messageId: String,
        val senderId: String?,
        val sender: String,
        val body: String,
        /** Server-provided message timestamp. */
        val sentAtMillis: Long,
        /** Local receipt time used only for the 24-hour cache expiry. */
        val receivedAtMillis: Long,
        val senderImageUrl: String?,
        val conversationType: NotificationPayload.ConversationType? = null,
        val conversationTitle: String? = null,
        val conversationImageUrl: String? = null,
        val url: String? = null,
        val replyUrl: String? = null,
        val readUrl: String? = null,
        val unreadCount: Int = 0,
        val tag: String? = null,
        val notificationId: Int = 0,
    )

    /**
     * Inserts or replaces a message by `messageId`, removes expired entries, sorts oldest→newest
     * by server timestamp then message id, and returns the bounded conversation history.
     */
    fun append(payload: NotificationPayload): List<ChatNotificationEntry> =
        synchronized(PERSISTENCE_LOCK) {
            val conversationId = payload.conversationId ?: return@synchronized emptyList()
            val messageId = payload.messageId ?: return@synchronized get(conversationId)
            if (payload.type != NotificationPayload.Type.CHAT) {
                return@synchronized get(conversationId)
            }

            val now = nowMillis()
            val incoming = ChatNotificationEntry(
                conversationId = conversationId,
                messageId = messageId,
                senderId = payload.senderId,
                sender = payload.sender ?: payload.title,
                body = payload.body,
                sentAtMillis = payload.sentAtMillis ?: now,
                receivedAtMillis = now,
                senderImageUrl = payload.senderImageUrl,
                conversationType = payload.conversationType,
                conversationTitle = payload.conversationTitle,
                conversationImageUrl = payload.conversationImageUrl,
                url = payload.url,
                replyUrl = payload.replyUrl,
                readUrl = payload.readUrl,
                unreadCount = payload.unreadCount,
                tag = payload.tag,
                notificationId = payload.notificationId,
            )
            val updated = normalize(
                storage.read().filterNot {
                    it.conversationId == conversationId && it.messageId == messageId
                } + incoming,
                now,
            )
            storage.write(updated)
            updated.filter { it.conversationId == conversationId }
        }

    /** Compatibility alias for existing integration code. */
    fun add(payload: NotificationPayload): List<ChatNotificationEntry> = append(payload)

    fun get(conversationId: String): List<ChatNotificationEntry> =
        synchronized(PERSISTENCE_LOCK) {
            if (conversationId.isBlank()) return@synchronized emptyList()
            val now = nowMillis()
            val stored = storage.read()
            val normalized = normalize(stored, now)
            if (normalized != stored) storage.write(normalized)
            normalized.filter { it.conversationId == conversationId }
        }

    /** Removes the inclusive server read boundary while preserving newer raced messages. */
    fun pruneThrough(
        conversationId: String,
        messageId: String,
    ): List<ChatNotificationEntry> = synchronized(PERSISTENCE_LOCK) {
        if (conversationId.isBlank() || messageId.isBlank()) {
            return@synchronized get(conversationId)
        }
        val now = nowMillis()
        val normalized = normalize(storage.read(), now)
        val conversationEntries = normalized.filter { it.conversationId == conversationId }
        val boundary = conversationEntries.firstOrNull { it.messageId == messageId }
        val boundaryNumeric = messageId.toBigIntegerOrNull()
        val boundaryTimestamp = boundary?.sentAtMillis
        val remaining = normalized.filterNot { entry ->
            if (entry.conversationId != conversationId) {
                false
            } else {
                val entryNumeric = entry.messageId.toBigIntegerOrNull()
                when {
                    // Backend message ids are numeric and define the authoritative read boundary.
                    boundaryNumeric != null && entryNumeric != null -> entryNumeric <= boundaryNumeric
                    // If ids are unexpectedly opaque but the exact boundary is cached, fall back to
                    // its server timestamp while retaining deterministic same-time id ordering.
                    boundaryTimestamp != null -> compareEntries(entry, boundary) <= 0
                    // An unknown opaque boundary cannot safely order unrelated entries.
                    else -> entry.messageId == messageId
                }
            }
        }
        storage.write(remaining)
        remaining.filter { it.conversationId == conversationId }
    }

    fun clear(conversationId: String) = synchronized(PERSISTENCE_LOCK) {
        if (conversationId.isBlank()) return@synchronized
        storage.write(storage.read().filterNot { it.conversationId == conversationId })
    }

    /** Compatibility alias for existing integration code. */
    fun clearConversation(conversationId: String) = clear(conversationId)

    fun clearAll() = synchronized(PERSISTENCE_LOCK) {
        storage.clear()
    }

    private fun normalize(
        entries: List<ChatNotificationEntry>,
        now: Long,
    ): List<ChatNotificationEntry> = entries
        .asSequence()
        .filter { entry ->
            entry.conversationId.isNotBlank() &&
                entry.messageId.isNotBlank() &&
                entry.receivedAtMillis >= now - expiryMillis &&
                entry.receivedAtMillis <= now + MAX_FUTURE_SKEW_MILLIS
        }
        // Be defensive against duplicate ids in old/corrupt persisted data. Last value wins.
        .groupBy { it.conversationId }
        .flatMap { (_, conversationEntries) ->
            conversationEntries
                .associateBy { it.messageId }
                .values
                .sortedWith(ENTRY_COMPARATOR)
                .takeLast(maxEntries)
        }
        .sortedWith(compareBy<ChatNotificationEntry> { it.conversationId }.then(ENTRY_COMPARATOR))

    internal interface Storage {
        fun read(): List<ChatNotificationEntry>
        fun write(entries: List<ChatNotificationEntry>)
        fun clear()
    }

    private class SharedPreferencesStorage(
        private val preferences: SharedPreferences,
    ) : Storage {
        override fun read(): List<ChatNotificationEntry> =
            decode(preferences.getString(KEY_HISTORY, null))

        override fun write(entries: List<ChatNotificationEntry>) {
            preferences.edit().putString(KEY_HISTORY, encode(entries)).apply()
        }

        override fun clear() {
            preferences.edit().remove(KEY_HISTORY).apply()
        }
    }

    internal class InMemoryStorage(
        initialValue: List<ChatNotificationEntry> = emptyList(),
    ) : Storage {
        var value: List<ChatNotificationEntry> = initialValue.toList()
            private set

        override fun read(): List<ChatNotificationEntry> = value.toList()

        override fun write(entries: List<ChatNotificationEntry>) {
            value = entries.toList()
        }

        override fun clear() {
            value = emptyList()
        }
    }

    internal companion object {
        const val DEFAULT_MAX_MESSAGES = 6
        const val DEFAULT_EXPIRY_MILLIS = 24L * 60L * 60L * 1_000L
        private const val MAX_FUTURE_SKEW_MILLIS = 5L * 60L * 1_000L
        private const val PREFS_NAME = "fenjoon_chat_notification_history"
        private const val KEY_HISTORY = "messages"
        private val PERSISTENCE_LOCK = Any()

        private val ENTRY_COMPARATOR = Comparator<ChatNotificationEntry>(::compareEntries)

        internal fun inMemory(
            nowMillis: () -> Long,
            maxMessages: Int = DEFAULT_MAX_MESSAGES,
            expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
            storage: InMemoryStorage = InMemoryStorage(),
        ): ChatNotificationHistoryStore = ChatNotificationHistoryStore(
            storage = storage,
            nowMillis = nowMillis,
            maxEntries = maxMessages,
            expiryMillis = expiryMillis,
        )

        private fun compareEntries(
            left: ChatNotificationEntry,
            right: ChatNotificationEntry,
        ): Int {
            val timestampComparison = left.sentAtMillis.compareTo(right.sentAtMillis)
            return if (timestampComparison != 0) {
                timestampComparison
            } else {
                compareMessageIds(left.messageId, right.messageId)
            }
        }

        /** Numeric backend ids sort numerically; opaque fallback ids remain deterministic. */
        private fun compareMessageIds(left: String, right: String): Int {
            val leftNumeric = left.toBigIntegerOrNull()
            val rightNumeric = right.toBigIntegerOrNull()
            return when {
                leftNumeric != null && rightNumeric != null -> leftNumeric.compareTo(rightNumeric)
                else -> left.compareTo(right)
            }
        }

        private fun encode(entries: List<ChatNotificationEntry>): String {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("conversationId", entry.conversationId)
                        put("messageId", entry.messageId)
                        putNullable("senderId", entry.senderId)
                        put("sender", entry.sender)
                        put("body", entry.body)
                        put("sentAtMillis", entry.sentAtMillis)
                        put("receivedAtMillis", entry.receivedAtMillis)
                        putNullable("senderImageUrl", entry.senderImageUrl)
                        putNullable("conversationType", entry.conversationType?.key)
                        putNullable("conversationTitle", entry.conversationTitle)
                        putNullable("conversationImageUrl", entry.conversationImageUrl)
                        putNullable("url", entry.url)
                        putNullable("replyUrl", entry.replyUrl)
                        putNullable("readUrl", entry.readUrl)
                        put("unreadCount", entry.unreadCount)
                        putNullable("tag", entry.tag)
                        put("notificationId", entry.notificationId)
                    },
                )
            }
            return array.toString()
        }

        private fun decode(raw: String?): List<ChatNotificationEntry> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val conversationId = item.optNonBlankString("conversationId") ?: continue
                        val messageId = item.optNonBlankString("messageId") ?: continue
                        add(
                            ChatNotificationEntry(
                                conversationId = conversationId,
                                messageId = messageId,
                                senderId = item.optNonBlankString("senderId"),
                                sender = item.optString("sender"),
                                body = item.optString("body"),
                                sentAtMillis = item.optLong("sentAtMillis", 0L),
                                receivedAtMillis = item.optLong("receivedAtMillis", 0L),
                                senderImageUrl = item.optNonBlankString("senderImageUrl"),
                                conversationType = NotificationPayload.ConversationType.fromKey(
                                    item.optNonBlankString("conversationType"),
                                ),
                                conversationTitle = item.optNonBlankString("conversationTitle"),
                                conversationImageUrl = item.optNonBlankString("conversationImageUrl"),
                                url = item.optNonBlankString("url"),
                                replyUrl = item.optNonBlankString("replyUrl"),
                                readUrl = item.optNonBlankString("readUrl"),
                                unreadCount = item.optInt("unreadCount", 0).coerceAtLeast(0),
                                tag = item.optNonBlankString("tag"),
                                notificationId = item.optInt(
                                    "notificationId",
                                    ChatNotificationIdentity.canonical(conversationId).id,
                                ),
                            ),
                        )
                    }
                }
            } catch (_: RuntimeException) {
                emptyList()
            }
        }

        private fun JSONObject.putNullable(key: String, value: String?) {
            if (value != null) put(key, value) else put(key, JSONObject.NULL)
        }

        private fun JSONObject.optNonBlankString(key: String): String? =
            optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}
