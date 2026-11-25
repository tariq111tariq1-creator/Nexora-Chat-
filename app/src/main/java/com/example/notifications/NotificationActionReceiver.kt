package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.example.MainActivity
import com.example.OfflineChatApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? OfflineChatApplication ?: return
        val repo = app.repository

        when (intent.action) {
            NotificationOrchestrator.ACTION_DIRECT_REPLY -> {
                val results = RemoteInput.getResultsFromIntent(intent)
                val replyText = results?.getCharSequence(NotificationOrchestrator.KEY_TEXT_REPLY)?.toString()?.trim()
                val conversationId = intent.getStringExtra(NotificationOrchestrator.EXTRA_CONVERSATION_ID) ?: return
                if (!replyText.isNullOrEmpty()) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            repo.sendTextMessage(conversationId = conversationId, text = replyText)
                            repo.clearUnread(conversationId)
                        }
                        NotificationOrchestrator.dismissNotification(context, conversationId.hashCode())
                        pendingResult.finish()
                    }
                }
            }

            NotificationOrchestrator.ACTION_MARK_READ -> {
                val conversationId = intent.getStringExtra(NotificationOrchestrator.EXTRA_CONVERSATION_ID) ?: return
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repo.clearUnread(conversationId) }
                    NotificationOrchestrator.dismissNotification(context, conversationId.hashCode())
                    pendingResult.finish()
                }
            }

            NotificationOrchestrator.ACTION_CALL_ANSWER -> {
                val callId = intent.getStringExtra(NotificationOrchestrator.EXTRA_CALL_ID) ?: return
                val peerId = intent.getStringExtra(NotificationOrchestrator.EXTRA_PEER_ID) ?: return
                val isVideo = intent.getBooleanExtra(NotificationOrchestrator.EXTRA_CALL_VIDEO, false)
                NotificationOrchestrator.dismissCallNotification(context)
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repo.acceptIncomingCall(callId, peerId, isVideo) }
                    pendingResult.finish()
                }
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        putExtra(NotificationOrchestrator.EXTRA_CALL_ID, callId)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }

            NotificationOrchestrator.ACTION_CALL_DECLINE -> {
                val callId = intent.getStringExtra(NotificationOrchestrator.EXTRA_CALL_ID) ?: return
                val peerId = intent.getStringExtra(NotificationOrchestrator.EXTRA_PEER_ID) ?: return
                NotificationOrchestrator.dismissCallNotification(context)
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repo.declineIncomingCall(callId, peerId) }
                    pendingResult.finish()
                }
            }

            NotificationOrchestrator.ACTION_TRANSFER_CANCEL -> {
                val transferId = intent.getStringExtra(NotificationOrchestrator.EXTRA_TRANSFER_ID) ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    repo.cancelTransfer(transferId)
                }
                NotificationOrchestrator.dismissNotification(context, transferId.hashCode())
            }

            NotificationOrchestrator.ACTION_DISCONNECT_MESH -> {
                CoroutineScope(Dispatchers.IO).launch {
                    repo.disconnectAll()
                }
            }
        }
    }
}
