package com.vanta.media.navigation

import kotlinx.serialization.Serializable

sealed interface VantaRoute {
    @Serializable data object Home : VantaRoute
    @Serializable data object Library : VantaRoute
    @Serializable data object Sources : VantaRoute
    @Serializable data object Settings : VantaRoute
    @Serializable data class Player(val canonicalKey: String, val sourceLinkId: String) : VantaRoute
}
