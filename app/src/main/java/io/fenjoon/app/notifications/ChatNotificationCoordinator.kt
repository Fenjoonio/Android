package io.fenjoon.app.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.service.notification.StatusBarNotification
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.text.BidiFormatter
import io.fenjoon.app.MainActivity
import io.fenjoon.app.R

/** A display-state snapshot used by the pure grouping transition planner. */
internal data class ChatChildGroupingState(
    val conversationId: String,
    val isInFixedGroup: Boolean,
)

/** Ordered structural changes needed to make the visible chat children internally consistent. */
internal sealed class ChatGroupingStep {
    data class GroupChild(val conversationId: String) : ChatGroupingStep()
    data class DetachChild(val conversationId: String) : ChatGroupingStep()
    data object PostSummary : ChatGroupingStep()
    data object CancelSummary : ChatGroupingStep()
}

/**
 * Plans only structural changes. The caller posts a fresh/updated child first, then applies these
 * steps against NotificationManager's active-notification snapshot.
 */
internal fun shouldGroupChatChild(
    activeConversationIds: Collection<String>,
    postedConversationId: String,
): Boolean = (activeConversationIds + postedConversationId).filter(String::isNotBlank).distinct().size >= 2

internal fun chatGroupingSteps(children: List<ChatChildGroupingState>): List<ChatGroupingStep> {
    val distinct = children
        .associateBy(ChatChildGroupingState::conversationId)
        .values
        .sortedBy(ChatChildGroupingState::conversationId)
    return when (distinct.size) {
        0 -> listOf(ChatGroupingStep.CancelSummary)
        1 -> buildList {
            val survivor = distinct.single()
            if (survivor.isInFixedGroup) {
                add(ChatGroupingStep.DetachChild(survivor.conversationId))
            }
            // For 2 -> 1 this ordering is intentional: detach before removing the summary.
            add(ChatGroupingStep.CancelSummary)
        }
        else -> buildList {
            distinct.filterNot(ChatChildGroupingState::isInFixedGroup).forEach { child ->
                add(ChatGroupingStep.GroupChild(child.conversationId))
            }
            add(ChatGroupingStep.PostSummary)
        }
    }
}

/**
 * Serializes every structural mutation of chat notifications and treats the system notification
 * shade as the source of truth. One visible conversation remains standalone; two or more use a
 * fixed native notification group plus one silent summary.
 */
internal object ChatNotificationCoordinator {
    private const val CONVERSATIONS_LINK = "https://app.fenjoon.io/conversations"
    private const val MAX_SUMMARY_LINES = 6
    private val mutationLock = Any()

    fun postFresh(
        context: Context,
        conversationId: String,
        builder: NotificationCompat.Builder,
    ): Boolean = postChild(context, conversationId, builder, silent = false)

    fun postSilent(
        context: Context,
        conversationId: String,
        builder: NotificationCompat.Builder,
    ) {
        postChild(context, conversationId, builder, silent = true)
    }

    fun cancelConversation(context: Context, conversationId: String) {
        if (conversationId.isBlank()) return
        synchronized(mutationLock) {
            val manager = notificationManager(context) ?: return
            cancelChildIdentities(manager, conversationId)
            reconcileLocked(context.applicationContext, manager, setOf(conversationId))
        }
    }

    /** Rebuilds grouping after process start without producing a new alert. */
    fun reconcile(context: Context) {
        synchronized(mutationLock) {
            val manager = notificationManager(context) ?: return
            reconcileLocked(context.applicationContext, manager)
        }
    }

    /** The child is already gone from the shade; clear its cache and rebalance survivors. */
    fun childDismissed(context: Context, conversationId: String) {
        if (conversationId.isBlank()) return
        synchronized(mutationLock) {
            val appContext = context.applicationContext
            ChatNotificationHistoryStore(appContext).clearConversation(conversationId)
            val manager = notificationManager(appContext) ?: return
            // Be defensive against OEMs that dispatch the delete intent before their active list
            // reflects the swipe. Never recover/repost the child the user just dismissed.
            cancelChildIdentities(manager, conversationId)
            reconcileLocked(appContext, manager, setOf(conversationId))
        }
    }

    /**
     * A summary swipe is an explicit clear of the represented snapshot. Do not run reconciliation:
     * doing so could immediately recreate the summary the user just dismissed.
     */
    fun summaryDismissed(context: Context, representedConversationIds: Collection<String>) {
        synchronized(mutationLock) {
            val appContext = context.applicationContext
            val manager = notificationManager(appContext)
            val history = ChatNotificationHistoryStore(appContext)
            representedConversationIds
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .forEach { conversationId ->
                    history.clearConversation(conversationId)
                    manager?.let { cancelChildIdentities(it, conversationId) }
                }
            manager?.cancel(ChatNotificationIdentity.summary.tag, ChatNotificationIdentity.summary.id)
        }
    }

    private fun postChild(
        context: Context,
        conversationId: String,
        builder: NotificationCompat.Builder,
        silent: Boolean,
    ): Boolean {
        if (conversationId.isBlank()) return false
        return synchronized(mutationLock) {
            val appContext = context.applicationContext
            val manager = notificationManager(appContext) ?: return@synchronized false
            val activeBefore = activeCanonicalChildren(manager)
            configureChildBuilder(
                builder = builder,
                grouped = shouldGroupChatChild(
                    activeBefore.map(ActiveChatChild::conversationId),
                    conversationId,
                ),
                silent = silent,
            )
            val identity = ChatNotificationIdentity.canonical(conversationId)
            manager.notify(identity.tag, identity.id, builder.build())
            reconcileLocked(appContext, manager)
            true
        }
    }

    private fun reconcileLocked(
        context: Context,
        manager: NotificationManager,
        excludedConversationIds: Set<String> = emptySet(),
    ) {
        val children = activeCanonicalChildren(manager)
            .filterNot { it.conversationId in excludedConversationIds }
        val byConversation = children.associateBy(ActiveChatChild::conversationId)
        val states = children.map { child ->
            ChatChildGroupingState(
                conversationId = child.conversationId,
                isInFixedGroup = child.notification.group == ChatNotificationIdentity.GROUP_KEY &&
                    child.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0,
            )
        }

        chatGroupingSteps(states).forEach { step ->
            when (step) {
                is ChatGroupingStep.GroupChild -> byConversation[step.conversationId]?.let { child ->
                    manager.notify(
                        child.tag,
                        child.id,
                        recoverChildForGrouping(context, child.notification, grouped = true),
                    )
                }
                is ChatGroupingStep.DetachChild -> byConversation[step.conversationId]?.let { child ->
                    manager.notify(
                        child.tag,
                        child.id,
                        recoverChildForGrouping(context, child.notification, grouped = false),
                    )
                }
                ChatGroupingStep.PostSummary -> manager.notify(
                    ChatNotificationIdentity.summary.tag,
                    ChatNotificationIdentity.summary.id,
                    buildSummary(context, children),
                )
                ChatGroupingStep.CancelSummary -> manager.cancel(
                    ChatNotificationIdentity.summary.tag,
                    ChatNotificationIdentity.summary.id,
                )
            }
        }
    }

    private fun configureChildBuilder(
        builder: NotificationCompat.Builder,
        grouped: Boolean,
        silent: Boolean,
    ) {
        builder
            .setGroup(if (grouped) ChatNotificationIdentity.GROUP_KEY else null)
            .setGroupSummary(false)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        if (silent) clearLegacyAlertFields(builder)
    }

    /** Visible for instrumentation tests that verify recoverBuilder preserves child card content. */
    internal fun recoverChildForGrouping(
        context: Context,
        notification: Notification,
        grouped: Boolean,
    ): Notification {
        val builder = Notification.Builder.recoverBuilder(context, notification)
            .setGroup(if (grouped) ChatNotificationIdentity.GROUP_KEY else null)
            .setGroupSummary(false)
            .setOnlyAlertOnce(true)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
            .setLights(0, 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
        }
        return builder.build()
    }

    private fun clearLegacyAlertFields(builder: NotificationCompat.Builder) {
        builder
            .setOnlyAlertOnce(true)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
            .setLights(0, 0, 0)
    }

    private fun buildSummary(context: Context, children: List<ActiveChatChild>): Notification {
        val conversationIds = children.map(ActiveChatChild::conversationId).distinct().sorted()
        val conversationCount = conversationIds.size
        val messageCount = children.sumOf { displayedMessageCount(it.notification) }
        val conversationsText = context.resources.getQuantityString(
            R.plurals.notification_chat_group_conversation_count,
            conversationCount,
            conversationCount,
        )
        val messagesText = context.resources.getQuantityString(
            R.plurals.notification_chat_group_message_count,
            messageCount,
            messageCount,
        )
        val orderedChildren = children.sortedByDescending { it.notification.`when` }
        val summaryLines = orderedChildren.take(MAX_SUMMARY_LINES).map { child ->
            summaryLine(context, child.notification)
        }
        val summaryStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(context.getString(R.string.notification_chat_group_summary_title))
            .also { style -> summaryLines.forEach(style::addLine) }
            .setSummaryText(messagesText)
        val publicVersion = publicChatNotification(context)
        val latestWhen = orderedChildren.firstOrNull()?.notification?.`when` ?: System.currentTimeMillis()
        val builder = NotificationCompat.Builder(context, NotificationChannels.CHAT)
            .setSmallIcon(R.drawable.adaptive_icon)
            .applyBrandIcon(context)
            .setContentTitle(context.getString(R.string.notification_chat_group_summary_title))
            .setContentText(summaryLines.firstOrNull() ?: messagesText)
            .setSubText(conversationsText)
            .setStyle(summaryStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setNumber(messageCount)
            .setWhen(latestWhen)
            .setShowWhen(false)
            .setGroup(ChatNotificationIdentity.GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(conversationsPendingIntent(context))
            .setDeleteIntent(
                NotificationDismissReceiver.summaryPendingIntent(
                    context = context,
                    requestCode = summaryDeleteRequestCode(conversationIds),
                    conversationIds = conversationIds,
                ),
            )
        clearLegacyAlertFields(builder)
        return builder.build()
    }

    private fun summaryLine(context: Context, notification: Notification): CharSequence {
        val bidi = BidiFormatter.getInstance()
        val title = notification.extras
            ?.getCharSequence(Notification.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_name)
        val body = notification.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
        val text = SpannableStringBuilder()
        val titleStart = text.length
        text.append(bidi.unicodeWrap(title))
        val titleEnd = text.length
        text.setSpan(
            StyleSpan(Typeface.BOLD),
            titleStart,
            titleEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        body?.let {
            text.append("  ")
            text.append(bidi.unicodeWrap(it))
        }
        return text
    }

    private fun publicChatNotification(context: Context): Notification =
        NotificationCompat.Builder(context, NotificationChannels.CHAT)
            .setSmallIcon(R.drawable.adaptive_icon)
            .applyBrandIcon(context)
            .setContentTitle(context.getString(R.string.notification_public_chat_title))
            .setContentText(context.getString(R.string.notification_public_chat_body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun conversationsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CONVERSATIONS_LINK), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            ChatNotificationIdentity.summary.id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun summaryDeleteRequestCode(conversationIds: List<String>): Int =
        31 * ChatNotificationIdentity.summary.id + conversationIds.joinToString("|").hashCode()

    private fun displayedMessageCount(notification: Notification): Int {
        if (notification.number > 0) return notification.number
        val visibleLines = notification.extras
            ?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.size
            .orZero()
        return visibleLines.coerceAtLeast(1)
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun activeCanonicalChildren(manager: NotificationManager): List<ActiveChatChild> =
        manager.activeNotifications
            .asSequence()
            .mapNotNull(::activeChatChild)
            .associateBy(ActiveChatChild::conversationId)
            .values
            .sortedBy(ActiveChatChild::conversationId)

    private fun activeChatChild(status: StatusBarNotification): ActiveChatChild? {
        val conversationId = ChatNotificationIdentity.parseCanonical(status.tag, status.id) ?: return null
        return ActiveChatChild(
            conversationId = conversationId,
            tag = status.tag,
            id = status.id,
            notification = status.notification,
        )
    }

    private fun cancelChildIdentities(manager: NotificationManager, conversationId: String) {
        val canonical = ChatNotificationIdentity.canonical(conversationId)
        val legacy = ChatNotificationIdentity.legacy(conversationId)
        manager.cancel(canonical.tag, canonical.id)
        manager.cancel(legacy.tag, legacy.id)
    }

    private fun NotificationCompat.Builder.applyBrandIcon(
        context: Context,
    ): NotificationCompat.Builder = apply {
        setColor(ContextCompat.getColor(context, R.color.fenjoon_icon_foreground))
        NotificationImageLoader.loadBrandIcon(context)?.let(::setLargeIcon)
    }

    private fun notificationManager(context: Context): NotificationManager? =
        context.applicationContext.getSystemService(NotificationManager::class.java)

    private data class ActiveChatChild(
        val conversationId: String,
        val tag: String,
        val id: Int,
        val notification: Notification,
    )
}
