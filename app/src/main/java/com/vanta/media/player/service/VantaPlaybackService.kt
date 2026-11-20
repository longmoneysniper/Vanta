package com.vanta.media.player.service

import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vanta.media.player.engine.VantaMpvController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class VantaPlaybackService : MediaSessionService() {

    @Inject lateinit var mpvController: VantaMpvController
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val bridgePlayer = object : SimpleBasePlayer(mainLooper) {
            override fun getState(): State {
                val s = mpvController.state.value
                return State.Builder()
                    .setAvailableCommands(
                        Player.Commands.Builder()
                            .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                            .build()
                    )
                    .setPlayWhenReady(s.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .setPlaybackState(Player.STATE_READY)
                    .setContentPositionMs(s.positionMs)
                    .build()
            }

            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                mpvController.togglePlayPause()
                invalidateState()
                return Futures.immediateVoidFuture()
            }

            override fun handleSeek(
                mediaItemIndex: Int,
                positionMs: Long,
                seekCommand: Int
            ): ListenableFuture<*> {
                mpvController.seekTo(positionMs)
                invalidateState()
                return Futures.immediateVoidFuture()
            }
        }
        mediaSession = MediaSession.Builder(this, bridgePlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
