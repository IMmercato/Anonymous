package com.example.anonymous

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.delay

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
fun XScreen(viewModel: VideoPlaybackViewModel = androidx.lifecycle.viewmodel.compose.viewModel(), onSwipeRight: () -> Unit) {
    val reelVideos = listOf(
        "https://immagini-b1484.web.app/videoplayback.mp4",
        "https://immagini-b1484.web.app/Elliptic%20Curve%20Cryptography.mp4"
    )

    val pagerState = rememberPagerState(pageCount = { reelVideos.size })

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        ReelItem(videoUrl = reelVideos[page], viewModel = viewModel, onSwipeRight = onSwipeRight)
    }
}

@SuppressLint("ClickableViewAccessibility")
@OptIn(UnstableApi::class)
@Composable
fun ReelItem(videoUrl: String, viewModel: VideoPlaybackViewModel, onSwipeRight: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var playbackPosition by rememberSaveable(videoUrl) {
        mutableStateOf(viewModel.getPlaybackPosition(videoUrl))
    }

    var isPlaying by remember { mutableStateOf(true) }
    var showLike by remember { mutableStateOf(false) }
    var centerX by remember { mutableStateOf(0f) }
    var centerY by remember { mutableStateOf(0f) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset += panChange
    }

    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
            prepare()
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            seekTo(playbackPosition)
            playWhenReady = isPlaying
        }
    }

    DisposableEffect(lifecycleOwner, videoUrl) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    playbackPosition = exoPlayer.currentPosition
                    viewModel.savePlaybackPosition(videoUrl, playbackPosition)
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_START -> {
                    exoPlayer.seekTo(viewModel.getPlaybackPosition(videoUrl))
                    exoPlayer.playWhenReady = isPlaying
                }
                Lifecycle.Event.ON_DESTROY -> {
                    playbackPosition = exoPlayer.currentPosition
                    viewModel.savePlaybackPosition(videoUrl, playbackPosition)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    //state.onGesture(zoom, pan, 0f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures (
                    onTap = {
                        isPlaying = !isPlaying
                        exoPlayer.playWhenReady = isPlaying
                    },
                    onDoubleTap = {
                        showLike = true
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    player = exoPlayer
                    setShutterBackgroundColor(Color.Black.toArgb())
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                    useController = false
                }
            },
            modifier = Modifier.matchParentSize()
        )

        val zoneRadius = 120.dp
        val boxSizePx = with(LocalDensity.current) { zoneRadius.toPx() * 2 }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (centerX - boxSizePx / 2).toInt(),
                        (centerY - boxSizePx / 2).toInt()
                    )
                }
                .size(zoneRadius * 2)
                .background(Color(0x66FF0000), shape = CircleShape)
        )

        Box( modifier = Modifier
            .height(50.dp)
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Transparent, shape = RectangleShape)
                    .blur(
                        radiusX = 10.dp,
                        radiusY = 10.dp,
                        edgeTreatment = BlurredEdgeTreatment(RoundedCornerShape(8.dp))
                    )
                    .pointerInput(Unit){
                        detectTapGestures (
                            onTap = {
                                Toast.makeText(context, "description", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
            )
            Text(
                "Caption...",
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (showLike) {
            LaunchedEffect(Unit) {
                delay(600)
                showLike = false
            }
            AnimatedVisibility( visible = showLike, modifier = Modifier.align(alignment = Alignment.CenterEnd) ) {
                Icon(Icons.Default.ThumbUp, "Like", modifier = Modifier.size(50.dp, 50.dp))
            }
        }
    }
}

//fun isInsideCenterZone(event: MotionEvent): Boolean {
//    val dx = event.x - centerX
//    val dy = event.y - centerY
//    val radius = 240f
//    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
//    return distance <= radius
//}
//
//val gestureDetector = GestureDetector(ctx,
//    object : GestureDetector.SimpleOnGestureListener() {
//        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
//            if (isInsideCenterZone(event)) {
//                isPlaying = !isPlaying
//                exoPlayer.playWhenReady = isPlaying
//                return true
//            }
//            return false
//        }
//
//        override fun onDoubleTap(event: MotionEvent): Boolean {
//            if (isInsideCenterZone(event)) {
//                showLike = true
//                return true
//            }
//            return false
//        }
//
//        override fun onScroll(
//            e1: MotionEvent?, e2: MotionEvent,
//            distanceX: Float, distanceY: Float
//        ): Boolean {
//            if (abs(distanceX) > abs(distanceY)) {
//                if (distanceX > 0) {
//                    onSwipeRight()
//                    return true
//                }
//            }
//            return false
//        }
//    }
//)