package io.fenjoon.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** Debug-only deterministic visual fixture, invokable with an explicit adb broadcast. */
class ChatNotificationFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOW) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val variant = intent.getStringExtra(EXTRA_VARIANT)?.lowercase() ?: VARIANT_DIRECT
        Thread(
            {
                try {
                    runVariant(appContext, variant)
                } finally {
                    pendingResult.finish()
                }
            },
            "ChatNotificationFixture",
        ).start()
    }

    private fun runVariant(context: Context, variant: String) {
        when (variant) {
            VARIANT_GROUP -> showFixture(context, GROUP_FIXTURE)
            VARIANT_MULTI -> {
                clearFixtures(context)
                showFixture(context, DIRECT_FIXTURE)
                showFixture(context, GROUP_FIXTURE)
            }
            VARIANT_ADD -> showFixture(context, THIRD_FIXTURE)
            VARIANT_REMOVE -> Notifier.cancelConversation(context, GROUP_CONVERSATION_ID)
            VARIANT_URGENT -> Notifier.show(context, urgentPayload())
            VARIANT_CLEAR -> clearFixtures(context)
            else -> showFixture(context, DIRECT_FIXTURE)
        }
    }

    private fun clearFixtures(context: Context) {
        listOf(DIRECT_FIXTURE, GROUP_FIXTURE, THIRD_FIXTURE).forEach { fixture ->
            Notifier.cancelConversation(context, fixture.conversationId)
        }
        NotificationManagerCompat.from(context).cancel(URGENT_TAG, URGENT_NOTIFICATION_ID)
    }

    private fun urgentPayload() = NotificationPayload.from(
        mapOf(
            "type" to "urgent",
            "title" to "بازبینی فوری",
            "body" to "یک داستان منتشرشده نیاز به بررسی فوری دارد.",
            "url" to "/moderation/stories/debug",
            "tag" to URGENT_TAG,
        ),
    )

    private fun showFixture(context: Context, fixture: Fixture) {
        val firstMessageTime = System.currentTimeMillis() - fixture.messages.size * 60_000L
        val history = ChatNotificationHistoryStore(context)
        history.clearConversation(fixture.conversationId)
        (0 until fixture.messages.lastIndex).forEach { index ->
            history.append(payload(fixture, index, firstMessageTime))
        }
        // Only the final line is posted, so each fixture produces one alert with cached history.
        Notifier.show(
            context,
            payload(fixture, fixture.messages.lastIndex, firstMessageTime),
        )
    }

    private fun payload(
        fixture: Fixture,
        index: Int,
        firstMessageTime: Long,
    ): NotificationPayload {
        val message = fixture.messages[index]
        return NotificationPayload(
            type = NotificationPayload.Type.CHAT,
            title = fixture.title,
            body = message.body,
            url = "https://app.fenjoon.io/conversations/${fixture.conversationId}",
            tag = null,
            imageUrl = null,
            sender = message.sender,
            conversationId = fixture.conversationId,
            messageId = (index + 1).toString(),
            senderId = message.senderId,
            sentAt = null,
            sentAtMillis = firstMessageTime + index * 60_000L,
            replyUrl = null,
            readUrl = null,
            unreadCount = fixture.messages.size,
            conversationType = fixture.conversationType,
            conversationTitle = fixture.title.takeIf { fixture.isGroup },
            senderImageUrl = null,
            conversationImageUrl = null,
            action = null,
            targetType = null,
            targetId = null,
            chapterId = null,
            blockId = null,
            notificationId = ChatNotificationIdentity.canonical(fixture.conversationId).id,
        )
    }

    private data class Fixture(
        val conversationId: String,
        val title: String,
        val conversationType: NotificationPayload.ConversationType,
        val messages: List<FixtureMessage>,
    ) {
        val isGroup: Boolean
            get() = conversationType == NotificationPayload.ConversationType.GROUP ||
                conversationType == NotificationPayload.ConversationType.CHANNEL
    }

    private data class FixtureMessage(
        val senderId: String,
        val sender: String,
        val body: String,
    )

    companion object {
        const val ACTION_SHOW = "io.fenjoon.app.debug.SHOW_CHAT_NOTIFICATION"
        const val EXTRA_VARIANT = "variant"
        const val VARIANT_DIRECT = "direct"
        const val VARIANT_GROUP = "group"
        const val VARIANT_MULTI = "multi"
        const val VARIANT_ADD = "add"
        const val VARIANT_REMOVE = "remove"
        const val VARIANT_URGENT = "urgent"
        const val VARIANT_CLEAR = "clear"

        private const val URGENT_TAG = "debug-urgent"
        private val URGENT_NOTIFICATION_ID = URGENT_TAG.hashCode()
        private const val DIRECT_CONVERSATION_ID = "00000000-0000-0000-0000-000000000001"
        private const val DIRECT_SENDER_ID = "00000000-0000-0000-0000-000000000002"
        private const val GROUP_CONVERSATION_ID = "00000000-0000-0000-0000-000000000003"
        private const val THIRD_CONVERSATION_ID = "00000000-0000-0000-0000-000000000004"

        private val DIRECT_FIXTURE = Fixture(
            conversationId = DIRECT_CONVERSATION_ID,
            title = "❤️ خانم خانم",
            conversationType = NotificationPayload.ConversationType.DIRECT,
            messages = listOf(
                "یکی باشگاه نبض نوشته بود خوبه",
                "یکی آرسین",
                "این نزدیک تره",
                "این البرزه ولی کلا زنونه اس",
                "عنوان جدید را بیشتر دوست دارم.",
                "پس همین نسخه را برای جلسه نگه می‌داریم.",
            ).map { body ->
                FixtureMessage(DIRECT_SENDER_ID, "❤️ خانم خانم", body)
            },
        )

        private val GROUP_FIXTURE = Fixture(
            conversationId = GROUP_CONVERSATION_ID,
            title = "باشگاه داستان‌نویسی",
            conversationType = NotificationPayload.ConversationType.GROUP,
            messages = listOf(
                FixtureMessage("group-1", "نسترن", "نسخه‌ی تازه را خواندم؛ پایانش بهتر شده."),
                FixtureMessage("group-2", "Sam سامان", "تا ساعت 20:16 یادداشت‌ها را می‌فرستم."),
                FixtureMessage("group-1", "نسترن", "ریتم فصل دوم حفظ می‌شود."),
                FixtureMessage("group-3", "رها", "من هم موافقم ✨"),
                FixtureMessage("group-2", "Sam سامان", "عنوان جدید را بیشتر دوست دارم."),
                FixtureMessage("group-3", "رها", "پس همین نسخه را برای جلسه نگه می‌داریم."),
            ),
        )

        private val THIRD_FIXTURE = Fixture(
            conversationId = THIRD_CONVERSATION_ID,
            title = "ویراستار",
            conversationType = NotificationPayload.ConversationType.DIRECT,
            messages = listOf(
                FixtureMessage("editor", "ویراستار", "نسخه‌ی نهایی آماده‌ی انتشار است."),
                FixtureMessage("editor", "ویراستار", "فقط عنوان فصل سوم را یک‌بار بررسی کن."),
            ),
        )
    }
}
