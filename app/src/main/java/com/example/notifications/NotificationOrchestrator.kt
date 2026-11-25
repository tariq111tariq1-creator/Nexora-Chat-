package com.example.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class InAppBannerEvent(
    val id: String,
    val conversationId: String,
    val senderName: String,
    val previewText: String,
    val isFile: Boolean = false
)

object NotificationOrchestrator {

    const val CHANNEL_MESSAGES = "offline_chat_messages"
    const val CHANNEL_CALLS = "offline_chat_calls"
    const val CHANNEL_TRANSFERS = "offline_chat_transfers"
    const val CHANNEL_SERVICE = "offline_chat_service"

    const val NOTIF_ID_SERVICE = 1001
    const val NOTIF_ID_CALL = 2001

    const val KEY_TEXT_REPLY = "key_offline_chat_reply"
    const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    const val EXTRA_PEER_ID = "extra_peer_id"
    const val EXTRA_CALL_ID = "extra_call_id"
    const val EXTRA_CALL_VIDEO = "extra_call_video"
    const val EXTRA_TRANSFER_ID = "extra_transfer_id"

    const val ACTION_DIRECT_REPLY = "com.example.action.DIRECT_REPLY"
    const val ACTION_MARK_READ = "com.example.action.MARK_READ"
    const val ACTION_CALL_ANSWER = "com.example.action.CALL_ANSWER"
    const val ACTION_CALL_DECLINE = "com.example.action.CALL_DECLINE"
    const val ACTION_TRANSFER_CANCEL = "com.example.action.TRANSFER_CANCEL"
    const val ACTION_DISCONNECT_MESH = "com.example.action.DISCONNECT_MESH"

    @Volatile
    var activeForegroundConversationId: String? = null

    @Volatile
    var isAppInForeground: Boolean = false

    private val _inAppBanners = MutableSharedFlow<InAppBannerEvent>(extraBufferCapacity = 16)
    val inAppBanners: SharedFlow<InAppBannerEvent> = _inAppBanners.asSharedFlow()

    fun emitInAppBanner(event: InAppBannerEvent) {
        _inAppBanners.tryEmit(event)
    }

    fun ensureChannelsCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notif_channel_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_messages_desc)
            enableVibration(true)
            enableLights(true)
        }

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val callsChannel = NotificationChannel(
            CHANNEL_CALLS,
            context.getString(R.string.notif_channel_calls),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_calls_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300)
            setSound(
                ringtoneUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val transfersChannel = NotificationChannel(
            CHANNEL_TRANSFERS,
            context.getString(R.string.notif_channel_transfers),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_transfers_desc)
            setShowBadge(false)
        }

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.notif_channel_service),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.notif_channel_service_desc)
            setShowBadge(false)
        }

        manager.createNotificationChannels(
            listOf(messagesChannel, callsChannel, transfersChannel, serviceChannel)
        )
    }

    fun buildServiceNotification(context: Context, connectedPeersCount: Int): Notification {
        ensureChannelsCreated(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getBroadcast(
            context,
            10,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_DISCONNECT_MESH
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = if (connectedPeersCount > 0) {
            context.getString(R.string.notif_service_peers_connected, connectedPeersCount)
        } else {
            context.getString(R.string.radar_idle)
        }

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_service_running))
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, context.getString(R.string.notif_action_disconnect), disconnectIntent)
            .build()
    }

    fun showIncomingMessageNotification(
        context: Context,
        conversationId: String,
        peerId: String,
        senderName: String,
        messageText: String,
        isFile: Boolean = false
    ) {
        if (isAppInForeground && activeForegroundConversationId == conversationId) {
            return
        }

        if (isAppInForeground) {
            emitInAppBanner(
                InAppBannerEvent(
                    id = "${conversationId}_${System.currentTimeMillis()}",
                    conversationId = conversationId,
                    senderName = senderName,
                    previewText = messageText,
                    isFile = isFile
                )
            )
            return
        }

        if (!canPostNotifications(context)) return
        ensureChannelsCreated(context)

        val openChatIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .build()

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            (conversationId + "_reply").hashCode(),
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_DIRECT_REPLY
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_PEER_ID, peerId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            context.getString(R.string.notif_action_reply),
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            (conversationId + "_read").hashCode(),
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_MARK_READ
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val senderPerson = Person.Builder().setName(senderName).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
            .addMessage(messageText, System.currentTimeMillis(), senderPerson)

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openChatIntent)
            .addAction(replyAction)
            .addAction(0, context.getString(R.string.notif_action_mark_read), markReadPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(conversationId.hashCode(), notification)
    }

    fun showIncomingCallNotification(
        context: Context,
        callId: String,
        peerId: String,
        callerName: String,
        isVideo: Boolean
    ) {
        if (!canPostNotifications(context)) return
        ensureChannelsCreated(context)

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            NOTIF_ID_CALL,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra(EXTRA_CALL_VIDEO, isVideo)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = PendingIntent.getBroadcast(
            context,
            NOTIF_ID_CALL + 1,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_CALL_ANSWER
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra(EXTRA_CALL_VIDEO, isVideo)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = PendingIntent.getBroadcast(
            context,
            NOTIF_ID_CALL + 2,
            Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_CALL_DECLINE
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_ID, peerId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val caller = Person.Builder().setName(callerName).setImportant(true).build()
        val callTypeLabel = if (isVideo) {
            context.getString(R.string.call_video_type)
        } else {
            context.getString(R.string.call_audio_type)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(callerName)
            .setContentText(callTypeLabel)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)

        val callStyleNotification = runCatching {
            NotificationCompat.Builder(context, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(callerName)
                .setContentText(callTypeLabel)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenIntent, true)
                .setContentIntent(fullScreenIntent)
                .setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(caller, declineIntent, answerIntent)
                        .setIsVideo(isVideo)
                )
                .build()
        }.getOrElse {
            builder
                .setStyle(NotificationCompat.BigTextStyle().bigText("$callerName\n$callTypeLabel"))
                .addAction(0, context.getString(R.string.notif_action_decline), declineIntent)
                .addAction(0, context.getString(R.string.notif_action_answer), answerIntent)
                .build()
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_CALL, callStyleNotification)
        }.onFailure {
            val fallback = builder
                .addAction(0, context.getString(R.string.notif_action_decline), declineIntent)
                .addAction(0, context.getString(R.string.notif_action_answer), answerIntent)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID_CALL, fallback)
        }
    }

    fun dismissCallNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_CALL)
    }

    fun showFileTransferProgress(
        context: Context,
        transferId: String,
        fileName: String,
        progressPercent: Int,
        speedText: String,
        isIncoming: Boolean,
        isComplete: Boolean = false,
        isFailed: Boolean = false
    ) {
        if (!canPostNotifications(context)) return
        ensureChannelsCreated(context)

        val openIntent = PendingIntent.getActivity(
            context,
            transferId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(fileName)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)

        when {
            isComplete -> {
                builder.setContentText(context.getString(R.string.transfer_completed))
                    .setProgress(0, 0, false)
                    .setAutoCancel(true)
            }
            isFailed -> {
                builder.setContentText(context.getString(R.string.transfer_failed))
                    .setProgress(0, 0, false)
                    .setAutoCancel(true)
            }
            else -> {
                val direction = if (isIncoming) {
                    context.getString(R.string.incoming_file)
                } else {
                    context.getString(R.string.outgoing_file)
                }
                val cancelPending = PendingIntent.getBroadcast(
                    context,
                    (transferId + "_cancel").hashCode(),
                    Intent(context, NotificationActionReceiver::class.java).apply {
                        action = ACTION_TRANSFER_CANCEL
                        putExtra(EXTRA_TRANSFER_ID, transferId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentText("$direction • $progressPercent% • $speedText")
                    .setProgress(100, progressPercent.coerceIn(0, 100), false)
                    .setOngoing(true)
                    .addAction(0, context.getString(R.string.cancel_transfer), cancelPending)
            }
        }

        NotificationManagerCompat.from(context).notify(transferId.hashCode(), builder.build())
    }

    fun dismissNotification(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
