package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object RadarRoute

@Serializable
object ChatsRoute

@Serializable
data class ChatRoomRoute(val conversationId: String)

@Serializable
object TransfersRoute

@Serializable
object SettingsRoute
