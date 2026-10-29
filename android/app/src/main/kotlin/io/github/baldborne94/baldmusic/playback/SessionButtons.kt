@file:OptIn(UnstableApi::class)

package io.github.baldborne94.baldmusic.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import io.github.baldborne94.baldmusic.R
import io.github.baldborne94.baldmusic.core.library.SessionControls
import io.github.baldborne94.baldmusic.core.library.SessionControls.Button

/** I tasti di [SessionControls.layout] come li vogliono la notifica e Android Auto. */
internal object SessionButtons {

    fun of(layout: List<Button>, liked: Boolean, shuffle: Boolean, repeatMode: Int, sleepLabel: String): ImmutableList<CommandButton> =
        ImmutableList.copyOf(layout.map { button(it, liked, shuffle, repeatMode, sleepLabel) })

    private fun button(button: Button, liked: Boolean, shuffle: Boolean, repeatMode: Int, sleepLabel: String): CommandButton = when (button) {
        Button.HEART -> build(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED, SessionControls.likeLabel(liked), PlaybackService.CMD_TOGGLE_LIKE)
        Button.SHUFFLE -> build(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF, SessionControls.shuffleLabel(shuffle), PlaybackService.CMD_SHUFFLE)
        Button.REPEAT -> build(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
            SessionControls.repeatLabel(repeatMode),
            PlaybackService.CMD_REPEAT,
        )
        // Timer di spegnimento anche dall'auto: un tocco per passo (SleepTimer), il nome dice quanto manca.
        Button.SLEEP -> CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_timer)
            .setDisplayName(sleepLabel)
            .setSessionCommand(SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY))
            .build()
        Button.BACK -> build(CommandButton.ICON_SKIP_BACK_15, SessionControls.BACK_LABEL, PlaybackService.CMD_BACK)
        Button.FORWARD -> build(CommandButton.ICON_SKIP_FORWARD_30, SessionControls.FORWARD_LABEL, PlaybackService.CMD_FORWARD)
    }

    private fun build(icon: Int, name: String, command: String) = CommandButton.Builder(icon)
        .setDisplayName(name)
        .setSessionCommand(SessionCommand(command, Bundle.EMPTY))
        .build()
}
