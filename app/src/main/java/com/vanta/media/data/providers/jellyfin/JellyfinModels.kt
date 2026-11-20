package com.vanta.media.data.providers.jellyfin

import kotlinx.serialization.Serializable

@Serializable
data class JellyfinServerProfile(
    val serverId: String,
    val name: String,
    val baseUrl: String,
    val userId: String,
    val accessToken: String
)

data class QuickConnectChallenge(
    val secret: String,
    val code: String
)
