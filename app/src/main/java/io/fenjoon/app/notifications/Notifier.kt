package io.fenjoon.app.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.text.BidiFormatter
import io.fenjoon.app.MainActivity
import io.fenjoon.app.R

/** Builds and posts native notifications using Android's standard system templates. */
object Notifier {
    private const val DEFAULT_LINK = "https://app.fenjoon.io"

    fun show(context: Context, payload: NotificationPayload) {
        if (payload.isChatReadControl) return

        if (payload.type == NotificationPayload.Type.CHAT) {
            val conversationId = payload.conversationId ?: return
            ChatNotificationCoordinator.postFresh(
                context,
                conversationId,
                buildFreshChat(context, payload),
            )
            return
        }

        val builder = when (payload.type) {
            NotificationPayload.Type.CHAT,
            NotificationPayload.Type.CHAT_READ -> return
            NotificationPayload.Type.SOCIAL ->
                buildSimple(context, payload, NotificationCompat.PRIORITY_HIGH)
            NotificationPayload.Type.URGENT ->
                buildUrgentNotificationBuilder(context, payload)
            NotificationPayload.Type.REMINDER ->
                buildSimple(context, payload, NotificationCompat.PRIORITY_LOW)
            NotificationPayload.Type.DEFAULT ->
                buildSimple(context, payload, NotificationCompat.PRIORITY_HIGH)
        }
        NotificationManagerCompat.from(context)
            .notify(payload.tag, payload.notificationId, builder.build())
    }

    /** Safe to call repeatedly; also removes stored snippets for the conversation. */
    fun cancelConversation(context: Context, conversationId: String) {
        if (conversationId.isBlank()) return
        ChatNotificationHistoryStore(context).clearConversation(conversationId)
        ChatNotificationCoordinator.cancelConversation(context, conversationId)
    }

    /** Applies an inclusive read boundary while retaining any newer raced messages. */
    internal fun applyReadThrough(context: Context, conversationId: String, messageId: String) {
        if (conversationId.isBlank() || messageId.isBlank()) return
        val remaining = ChatNotificationHistoryStore(context).pruneThrough(conversationId, messageId)
        if (remaining.isEmpty()) {
            cancelConversation(context, conversationId)
        } else {
            refreshConversation(context, conversationId, remaining)
        }
    }

    /** Reposts a fully actionable card after only part of local history was pruned. */
    internal fun refreshConversation(
        context: Context,
        conversationId: String,
        messages: List<ChatNotificationHistoryStore.ChatNotificationEntry>,
    ) {
        if (conversationId.isBlank() || messages.isEmpty()) return
        val latest = messages.last()
        val identity = ChatNotificationIdentity.canonical(conversationId)
        val payload = payloadFromHistory(latest, identity)
        val builder = buildChatNotificationBuilder(
            context = context,
            payload = payload,
            messages = messages,
            onlyAlertOnce = true,
        )
        ChatNotificationCoordinator.postSilent(context, conversationId, builder)
    }

    /** Reposts a canonical silent failure card without bypassing grouping reconciliation. */
    internal fun showReplyFailure(
        context: Context,
        conversationId: String,
        messageId: String?,
        readUrl: String?,
        sender: String?,
        title: String?,
        link: String?,
    ) {
        if (conversationId.isBlank()) return
        val identity = ChatNotificationIdentity.canonical(conversationId)
        val payload = NotificationPayload(
            type = NotificationPayload.Type.CHAT,
            title = title ?: sender ?: context.getString(R.string.app_name),
            body = context.getString(R.string.notification_reply_failed),
            url = link,
            tag = identity.tag,
            imageUrl = null,
            sender = sender,
            conversationId = conversationId,
            messageId = messageId,
            senderId = null,
            sentAt = null,
            sentAtMillis = System.currentTimeMillis(),
            replyUrl = null,
            readUrl = readUrl,
            unreadCount = 1,
            conversationType = null,
            conversationTitle = title,
            senderImageUrl = null,
            conversationImageUrl = null,
            action = null,
            targetType = null,
            targetId = null,
            chapterId = null,
            blockId = null,
            notificationId = identity.id,
        )
        val builder = NotificationCompat.Builder(context, NotificationChannels.CHAT)
            .setSmallIcon(R.drawable.adaptive_icon)
            .applyBrandIcon(context)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicChatNotification(context))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setNumber(1)
            .setContentIntent(contentIntent(context, payload))
        notificationDeleteIntent(context, payload)?.let(builder::setDeleteIntent)
        ChatNotificationCoordinator.postSilent(context, conversationId, builder)
    }

    private fun payloadFromHistory(
        latest: ChatNotificationHistoryStore.ChatNotificationEntry,
        identity: ChatNotificationIdentity,
    ) = NotificationPayload(
        type = NotificationPayload.Type.CHAT,
        title = latest.conversationTitle ?: latest.sender,
        body = latest.body,
        url = latest.url,
        tag = identity.tag,
        imageUrl = latest.senderImageUrl,
        sender = latest.sender,
        conversationId = latest.conversationId,
        messageId = latest.messageId,
        senderId = latest.senderId,
        sentAt = null,
        sentAtMillis = latest.sentAtMillis,
        replyUrl = latest.replyUrl,
        readUrl = latest.readUrl,
        unreadCount = latest.unreadCount,
        conversationType = latest.conversationType,
        conversationTitle = latest.conversationTitle,
        senderImageUrl = latest.senderImageUrl,
        conversationImageUrl = latest.conversationImageUrl,
        action = null,
        targetType = null,
        targetId = null,
        chapterId = null,
        blockId = null,
        notificationId = identity.id,
    )

    private fun baseBuilder(
        context: Context,
        payload: NotificationPayload,
    ): NotificationCompat.Builder = NotificationCompat.Builder(context, payload.type.channelId)
        .setSmallIcon(R.drawable.adaptive_icon)
        .applyBrandIcon(context)
        .setContentTitle(payload.title)
        .setContentText(payload.body)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicNotification(context, payload.type))
        .setAutoCancel(true)
        .setContentIntent(contentIntent(context, payload))

    private fun buildSimple(
        context: Context,
        payload: NotificationPayload,
        priority: Int,
    ): NotificationCompat.Builder {
        val builder = baseBuilder(context, payload).setPriority(priority)
        applyLegacyAlertPolicy(builder, payload.type)
        if (payload.body.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
        }
        return builder
    }

    internal fun buildUrgentNotificationBuilder(
        context: Context,
        payload: NotificationPayload,
    ): NotificationCompat.Builder {
        val builder = baseBuilder(context, payload)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicUrgentNotification(context, payload))
        applyLegacyAlertPolicy(builder, NotificationPayload.Type.URGENT)
        if (payload.body.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
        }
        return builder
    }

    private fun publicUrgentNotification(
        context: Context,
        payload: NotificationPayload,
    ): Notification {
        val title = payload.title.ifBlank {
            context.getString(R.string.notification_public_urgent_title)
        }
        val builder = NotificationCompat.Builder(context, NotificationChannels.URGENT)
            .setSmallIcon(R.drawable.adaptive_icon)
            .applyBrandIcon(context)
            .setContentTitle(title)
            .setContentText(payload.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (payload.body.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
        }
        return builder.build()
    }

    private fun publicNotification(
        context: Context,
        type: NotificationPayload.Type,
    ): Notification {
        val titleRes = when (type) {
            NotificationPayload.Type.SOCIAL -> R.string.notification_public_social_title
            NotificationPayload.Type.URGENT -> R.string.notification_public_urgent_title
            NotificationPayload.Type.REMINDER -> R.string.notification_public_reminder_title
            else -> R.string.notification_public_general_title
        }
        val priority = if (type == NotificationPayload.Type.REMINDER) {
            NotificationCompat.PRIORITY_LOW
        } else {
            NotificationCompat.PRIORITY_HIGH
        }
        return NotificationCompat.Builder(context, type.channelId)
            .setSmallIcon(R.drawable.adaptive_icon)
            .applyBrandIcon(context)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(R.string.notification_public_generic_body))
            .setPriority(priority)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun applyLegacyAlertPolicy(
        builder: NotificationCompat.Builder,
        type: NotificationPayload.Type,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        if (type == NotificationPayload.Type.REMINDER) {
            builder.setDefaults(0)
        } else {
            builder.setDefaults(Notification.DEFAULT_ALL)
        }
    }

    private fun buildFreshChat(
        context: Context,
        payload: NotificationPayload,
    ): NotificationCompat.Builder {
        val conversationId = payload.conversationId
        val storedMessages = if (conversationId != null) {
            ChatNotificationHistoryStore(context).add(payload)
        } else {
            emptyList()
        }
        val messages = storedMessages.ifEmpty { listOf(payload.toHistoryEntry()) }
        return buildChatNotificationBuilder(
            context = context,
            payload = payload,
            messages = messages,
            onlyAlertOnce = false,
        )
    }

    internal fun buildChatNotificationBuilder(
        context: Context,
        payload: NotificationPayload,
        messages: List<ChatNotificationHistoryStore.ChatNotificationEntry>,
        onlyAlertOnce: Boolean,
        avatarLoader: (Context, String?) -> Bitmap? = { loaderContext, avatarUrl ->
            NotificationImageLoader.loadChatAvatar(loaderContext, avatarUrl)
        },
    ): NotificationCompat.Builder {
        val presentation = ChatNotificationPresentation.from(messages)
        val latest = messages.last()
        val builder = NotificationCompat.Builder(context, NotificationChannels.CHAT)
            // Title/text remain available to launchers, accessibility services, and degraded hosts.
            .setContentTitle(presentation.title)
            .setContentText(formatInboxLine(presentation, presentation.messages.last()))
            .setSmallIcon(R.drawable.adaptive_icon)
            .applyChatIcon(context, presentation.avatarUrl, avatarLoader)
            .setStyle(inboxStyle(presentation))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicChatNotification(context))
            .setColorized(false)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setAutoCancel(true)
            .setNumber(presentation.unreadCount)
            .setWhen(latest.sentAtMillis)
            .setShowWhen(true)
            .setContentIntent(contentIntent(context, payload))

        if (payload.replyUrl != null) builder.addAction(replyAction(context, payload))
        markReadAction(context, payload)?.let(builder::addAction)
        notificationDeleteIntent(context, payload)?.let(builder::setDeleteIntent)
        return builder
    }

    private fun publicChatNotification(context: Context) =
        NotificationCompat.Builder(context, NotificationChannels.CHAT)
            .setSmallIcon(R.drawable.adaptive_icon)
            .applyBrandIcon(context)
            .setContentTitle(context.getString(R.string.notification_public_chat_title))
            .setContentText(context.getString(R.string.notification_public_chat_body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun inboxStyle(
        presentation: ChatNotificationPresentation,
    ): NotificationCompat.InboxStyle = NotificationCompat.InboxStyle()
        .setBigContentTitle(presentation.title)
        .also { style ->
            presentation.messages.forEach { message ->
                style.addLine(formatInboxLine(presentation, message))
            }
        }

    private fun formatInboxLine(
        presentation: ChatNotificationPresentation,
        message: MessageLine,
    ): CharSequence {
        val bidi = BidiFormatter.getInstance()
        val body = bidi.unicodeWrap(message.body)
        if (!presentation.isGroupConversation) return body

        val text = SpannableStringBuilder()
        val senderStart = text.length
        text.append(bidi.unicodeWrap(message.sender))
        val senderEnd = text.length
        text.setSpan(
            StyleSpan(Typeface.BOLD),
            senderStart,
            senderEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        text.append(": ")
        text.append(body)
        return text
    }

    private fun NotificationPayload.toHistoryEntry(): ChatNotificationHistoryStore.ChatNotificationEntry {
        val now = System.currentTimeMillis()
        return ChatNotificationHistoryStore.ChatNotificationEntry(
            conversationId = conversationId.orEmpty(),
            messageId = messageId.orEmpty(),
            senderId = senderId,
            sender = sender ?: title,
            body = body,
            sentAtMillis = sentAtMillis ?: now,
            receivedAtMillis = now,
            senderImageUrl = senderImageUrl ?: imageUrl,
            conversationType = conversationType,
            conversationTitle = conversationTitle,
            conversationImageUrl = conversationImageUrl,
            url = url,
            replyUrl = replyUrl,
            readUrl = readUrl,
            unreadCount = unreadCount,
            tag = tag,
            notificationId = notificationId,
        )
    }

    private fun replyAction(context: Context, payload: NotificationPayload): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(DirectReplyReceiver.KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.notification_reply_placeholder))
            .build()
        val intent = Intent(context, DirectReplyReceiver::class.java).apply {
            putExtra(DirectReplyReceiver.EXTRA_NOTIFICATION_ID, payload.notificationId)
            putExtra(DirectReplyReceiver.EXTRA_TAG, payload.tag)
            putExtra(DirectReplyReceiver.EXTRA_CONVERSATION_ID, payload.conversationId)
            putExtra(DirectReplyReceiver.EXTRA_MESSAGE_ID, payload.messageId)
            putExtra(DirectReplyReceiver.EXTRA_REPLY_URL, payload.replyUrl)
            putExtra(DirectReplyReceiver.EXTRA_READ_URL, payload.readUrl)
            putExtra(DirectReplyReceiver.EXTRA_SENDER, payload.sender)
            putExtra(DirectReplyReceiver.EXTRA_TITLE, payload.title)
            putExtra(DirectReplyReceiver.EXTRA_LINK, payload.url)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(payload.notificationId, 1),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.notification_reply_label),
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(
        context: Context,
        payload: NotificationPayload,
    ): NotificationCompat.Action? {
        val conversationId = payload.conversationId ?: return null
        if (payload.messageId == null || payload.readUrl == null) return null
        val intent = Intent(context, MarkConversationReadReceiver::class.java).apply {
            action = MarkConversationReadReceiver.ACTION_MARK_READ
            putNotificationActionExtras(payload, conversationId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(payload.notificationId, 2),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.notification_mark_read_label),
            pendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun notificationDeleteIntent(
        context: Context,
        payload: NotificationPayload,
    ): PendingIntent? {
        val conversationId = payload.conversationId ?: return null
        return NotificationDismissReceiver.pendingIntent(
            context = context,
            requestCode = requestCode(payload.notificationId, 3),
            conversationId = conversationId,
        )
    }

    private fun contentIntent(context: Context, payload: NotificationPayload): PendingIntent {
        if (payload.type == NotificationPayload.Type.CHAT) {
            payload.conversationId?.let { conversationId ->
                return ChatNotificationOpenIntent.pendingIntent(
                    context = context,
                    requestCode = requestCode(payload.notificationId, 0),
                    conversationId = conversationId,
                    messageId = payload.messageId,
                    readUrl = payload.readUrl,
                    notificationId = payload.notificationId,
                    tag = payload.tag,
                    link = payload.url,
                )
            }
        }
        // Non-chat notifications carry relative web paths (e.g. "/story/123"). MainActivity's
        // URI validator only accepts the absolute https://app.fenjoon.io form, so resolve any
        // relative link against the web origin before building the intent — otherwise the tap
        // silently falls back to the home page.
        val rawLink = payload.url ?: DEFAULT_LINK
        val link = if (rawLink.startsWith("/") || rawLink.startsWith("#")) {
            DEFAULT_LINK + rawLink
        } else {
            rawLink
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            setClass(context, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            payload.notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun Intent.putNotificationActionExtras(
        payload: NotificationPayload,
        conversationId: String,
    ) {
        putExtra(NotificationActionExtras.CONVERSATION_ID, conversationId)
        putExtra(NotificationActionExtras.MESSAGE_ID, payload.messageId)
        putExtra(NotificationActionExtras.READ_URL, payload.readUrl)
        putExtra(NotificationActionExtras.NOTIFICATION_ID, payload.notificationId)
        putExtra(NotificationActionExtras.TAG, payload.tag)
        putExtra(NotificationActionExtras.LINK, payload.url)
    }

    private fun NotificationCompat.Builder.applyChatIcon(
        context: Context,
        avatarUrl: String?,
        avatarLoader: (Context, String?) -> Bitmap?,
    ): NotificationCompat.Builder = apply {
        setColor(ContextCompat.getColor(context, R.color.fenjoon_icon_foreground))
        val icon = avatarUrl?.let { avatarLoader(context, it) }
            ?: NotificationImageLoader.loadBrandIcon(context)
        icon?.let(::setLargeIcon)
    }

    private fun NotificationCompat.Builder.applyBrandIcon(
        context: Context,
    ): NotificationCompat.Builder = apply {
        setColor(ContextCompat.getColor(context, R.color.fenjoon_icon_foreground))
        NotificationImageLoader.loadBrandIcon(context)?.let(::setLargeIcon)
    }

    private fun requestCode(notificationId: Int, slot: Int): Int = notificationId * 31 + slot
}
