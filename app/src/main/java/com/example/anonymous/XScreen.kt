package com.example.anonymous

import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class VideoPlaybackViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    fun savePlaybackPosition(videoUrl: String, position: Long) {
        savedStateHandle["playback_position_$videoUrl"] = position
    }
    fun getPlaybackPosition(videoUrl: String): Long {
        return savedStateHandle.get<Long>("playback_position_$videoUrl") ?: 0L
    }
}

@Composable
fun XScreen(viewModel: VideoPlaybackViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val reelVideos = listOf(
        "https://immagini-b1484.web.app/videoplayback.mp4",
        "https://immagini-b1484.web.app/Elliptic%20Curve%20Cryptography.mp4"
    )

    val pagerState = rememberPagerState(pageCount = { reelVideos.size })

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        ReelItem(videoUrl = reelVideos[page], viewModel = viewModel)
    }
}


@OptIn(UnstableApi::class)
@Composable
fun ReelItem(videoUrl: String, viewModel: VideoPlaybackViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

// Use the videoUrl as part of the key so that when switching videos the saved value is specific.
    var playbackPosition by rememberSaveable(videoUrl) {
        mutableStateOf(viewModel.getPlaybackPosition(videoUrl))
    }

// Create (and remember) an ExoPlayer instance tied to the videoUrl.
    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
                prepare()
                // Restore the playback position
                seekTo(playbackPosition)
                playWhenReady = true
            }
    }

    DisposableEffect(lifecycleOwner, videoUrl) {
        // Observe lifecycle events.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // When the UI is no longer visible, pause playback and save the current position.
                Lifecycle.Event.ON_STOP -> {
                    playbackPosition = exoPlayer.currentPosition
                    viewModel.savePlaybackPosition(videoUrl, playbackPosition)
                    exoPlayer.pause()
                }
                // When the UI becomes visible again, seek to the saved position and resume playback.
                Lifecycle.Event.ON_START -> {
                    exoPlayer.seekTo(viewModel.getPlaybackPosition(videoUrl))
                    exoPlayer.playWhenReady = true
                }
                // On destruction, save the current position.
                Lifecycle.Event.ON_DESTROY -> {
                    playbackPosition = exoPlayer.currentPosition
                    viewModel.savePlaybackPosition(videoUrl, playbackPosition)
                }
                else -> { /* ignore other events */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Hook up the player instance.
                player = exoPlayer
                setShutterBackgroundColor(Color.Black.toArgb())
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                useController = false
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}