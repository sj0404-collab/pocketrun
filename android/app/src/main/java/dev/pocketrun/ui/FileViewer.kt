package dev.pocketrun.ui

import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pocketrun.core.FileTree
import kotlinx.coroutines.delay
import java.io.File

/**
 * The built-in viewer: what the app can show itself it shows itself, and what it
 * cannot it hands to a system app. No third-party player, no image library - the
 * platform already has a decoder and a player, and a project file should not cost
 * the app megabytes of dependencies to look at.
 */
@Composable
fun FileViewerDialog(preview: AppViewModel.FilePreview, viewModel: AppViewModel) {
    val file = when (preview) {
        is AppViewModel.FilePreview.Image -> null
        is AppViewModel.FilePreview.Audio -> preview.file
        is AppViewModel.FilePreview.Video -> preview.file
        is AppViewModel.FilePreview.Other -> preview.file
        else -> null
    }

    Dialog(
        onDismissRequest = viewModel::closePreview,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF101014)),
        ) {
            when (preview) {
                is AppViewModel.FilePreview.Loading -> Centered {
                    CircularProgressIndicator(color = Color.White)
                }
                is AppViewModel.FilePreview.Image -> ImageViewer(preview.bitmap, preview.name)
                is AppViewModel.FilePreview.Audio -> AudioViewer(preview.file)
                is AppViewModel.FilePreview.Video -> VideoViewer(preview.file)
                is AppViewModel.FilePreview.Other -> OtherViewer(preview.file, preview.size, viewModel)
                is AppViewModel.FilePreview.Failed -> Centered {
                    Text(preview.message, color = Color.White)
                }
                else -> Unit
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                file?.let {
                    Text(
                        it.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.openExternally(it) }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = "Открыть системным", tint = Color.White)
                    }
                } ?: Spacer(Modifier.weight(1f))
                IconButton(onClick = viewModel::closePreview) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** A photo, with the two gestures people expect: pinch to zoom, drag to pan. */
@Composable
private fun ImageViewer(bitmap: android.graphics.Bitmap, name: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = name,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
        )
    }
}

@Composable
private fun AudioViewer(file: File) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }

    DisposableEffect(file) {
        val created = try {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { ready = true; duration = it.duration.coerceAtLeast(0) }
                setOnCompletionListener { playing = false; position = 0 }
                setOnErrorListener { _, what, extra ->
                    failed = "не удалось открыть аудио (what=$what extra=$extra)"
                    true
                }
                prepareAsync()
            }
        } catch (t: Throwable) {
            failed = t.message ?: "не удалось открыть аудио"
            null
        }
        player = created
        onDispose {
            created?.release()
            player = null
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            position = player?.currentPosition ?: 0
            delay(300)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        failed?.let { Text(it, color = Color.White) }
        Spacer(Modifier.height(16.dp))
        IconButton(onClick = {
            val p = player ?: return@IconButton
            if (playing) {
                p.pause()
                playing = false
            } else {
                p.start()
                playing = true
            }
        }, enabled = ready && failed == null) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Пауза" else "Играть",
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
        Slider(
            value = position.toFloat(),
            onValueChange = { player?.seekTo(it.toInt()) },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            enabled = ready && duration > 0,
        )
        Text(
            "${mmss(position)} / ${mmss(duration)}",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun VideoViewer(file: File) {
    var failed by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    setMediaController(MediaController(context))
                    setOnErrorListener { _, what, extra ->
                        failed = "видео не открылось (what=$what extra=$extra)"
                        true
                    }
                    setVideoPath(file.absolutePath)
                    setOnPreparedListener { start() }
                }
            },
        )
        failed?.let {
            Text(it, color = Color.White, modifier = Modifier.padding(24.dp))
        }
    }
}

@Composable
private fun OtherViewer(file: File, size: String, viewModel: AppViewModel) {
    val kind = FileTree.kindOf(file)
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(file.name, color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "${kind.title} · $size",
            color = Color.LightGray,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Приложение не умеет показывать этот тип файла — откройте его другим приложением.",
            color = Color.LightGray,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { viewModel.openExternally(file) }) {
            Text("Открыть системным приложением", color = Color.White)
        }
    }
}

private fun mmss(ms: Int): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
