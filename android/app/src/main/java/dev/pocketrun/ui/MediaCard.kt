package dev.pocketrun.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pocketrun.core.FileKind
import dev.pocketrun.core.FileText
import kotlinx.coroutines.delay
import java.io.File

/**
 * Something the agent made, shown in the conversation rather than only in the file
 * tree.
 *
 * Images appear inline, animations animate (an ImageDecoder drawable, not a
 * still), sound and video get a real transport. Four actions sit under it: open it
 * full-screen, save it off the device, share it, and - the one an image really
 * needs - ask for another version, either the same prompt again or a new one.
 *
 * Everything here is plain Android: MediaPlayer, VideoView and the platform image
 * decoder, the same three the file viewer uses, so there is no second player to
 * keep working.
 */
@Composable
fun MediaCard(
    file: File,
    kind: FileKind,
    prompt: String?,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
    onReprompt: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            when (kind) {
                FileKind.IMAGE -> ImagePreview(file)
                FileKind.AUDIO -> AudioPreview(file)
                FileKind.VIDEO -> VideoPreview(file)
                else -> UnknownPreview(file, kind)
            }

            Spacer(Modifier.height(6.dp))
            Text(file.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text(
                "${kind.title} · ${FileText.human(file.length())}" +
                    if (kind == FileKind.IMAGE && file.name.endsWith(".gif")) " · анимация" else "",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            prompt?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "запрос: $it",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onOpen) {
                    Icon(Icons.Filled.Visibility, contentDescription = "Открыть", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onSave) {
                    Icon(Icons.Filled.Download, contentDescription = "Сохранить на телефон", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Поделиться", modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onRegenerate,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ещё раз", fontSize = 12.sp)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onReprompt, modifier = Modifier.height(30.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Промт", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * A picture, inline.
 *
 * The card shows a still thumbnail. An animation is not played here: keeping it
 * running in a scrolling list costs a repaint per frame per card, and the same
 * drawable animates for free in the full-screen viewer, which is one deliberate
 * image on screen rather than a dozen. The label says so rather than letting a
 * frozen GIF pass for a still.
 */
@Composable
private fun ImagePreview(file: File) {
    val animated = isAnimated(file)
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        bitmap = null
        failed = null
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .onSuccess { bitmap = it }
            .onFailure { failed = "не удалось прочитать изображение" }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .background(Color(0xFF101014), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            )
            failed != null -> Text(failed!!, color = Color.White, fontSize = 12.sp)
            else -> Text("…", color = Color.White)
        }
        if (animated) {
            Text(
                "анимация — откройте",
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color(0x88000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** True for a GIF or animated WebP the platform can play, not a plain still. */
fun isAnimated(file: File): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val name = file.name.lowercase()
    if (!name.endsWith(".gif") && !name.endsWith(".webp")) return false
    return runCatching {
        val source = android.graphics.ImageDecoder.createSource(file)
        android.graphics.ImageDecoder.decodeDrawable(source) is AnimatedImageDrawable
    }.getOrDefault(false)
}

/** A sound with a real transport, so the user can hear a second and judge it. */
@Composable
private fun AudioPreview(file: File) {
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var failed by remember(file) { mutableStateOf<String?>(null) }
    var position by remember(file) { mutableIntStateOf(0) }
    var duration by remember(file) { mutableIntStateOf(0) }

    DisposableEffect(file) {
        val created = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { duration = it.duration.coerceAtLeast(0); ready = true }
                setOnCompletionListener { playing = false; position = 0 }
                setOnErrorListener { _, what, extra ->
                    failed = "аудио не открылось (what=$what extra=$extra)"; true
                }
                prepareAsync()
            }
        }.getOrNull()
        player = created
        onDispose {
            created?.release()
            player = null
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            position = player?.currentPosition ?: 0
            delay(250)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(
            onClick = {
                val p = player ?: return@IconButton
                if (playing) { p.pause(); playing = false } else { p.start(); playing = true }
            },
            enabled = ready && failed == null,
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Пауза" else "Играть",
            )
        }
        Column(Modifier.weight(1f)) {
            Slider(
                value = position.toFloat(),
                onValueChange = { player?.seekTo(it.toInt()) },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = ready && duration > 0,
            )
            Text(
                failed ?: "${mmss(position)} / ${mmss(duration)}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (failed == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun VideoPreview(file: File) {
    var failed by remember(file) { mutableStateOf<String?>(null) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .background(Color(0xFF101014), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            factory = { context ->
                VideoView(context).apply {
                    setMediaController(MediaController(context))
                    setOnErrorListener { _, what, extra ->
                        failed = "видео не открылось (what=$what extra=$extra)"; true
                    }
                    setVideoPath(file.absolutePath)
                    setOnPreparedListener { it.isLooping = true; start() }
                }
            },
        )
        failed?.let { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
    }
}

@Composable
private fun UnknownPreview(file: File, kind: FileKind) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Файл: ${kind.title}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun mmss(ms: Int): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
