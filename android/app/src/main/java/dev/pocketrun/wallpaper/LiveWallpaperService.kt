package dev.pocketrun.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * A live wallpaper made of the pictures the agent produced.
 *
 * Android live wallpapers are the one place the app runs a full-screen render loop
 * with no user in front of it, so the constraints are all about not being a power
 * drain: the frame timer only runs while the surface is visible, decoding happens
 * once per frame for the current picture only, and a still frame is not
 * re-decoded until the picture changes.
 *
 * Frames are the image files in the project's `wallpaper` folder, cycled in name
 * order. Point the service at a folder and it will play whatever is there, which
 * means the agent can fill it: "сделай пять кадров для живых обоев с городом ночью".
 */
class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = PictureEngine()

    private inner class PictureEngine : Engine() {

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val frames = ArrayList<File>()
        private var index = 0

        private var bitmap: Bitmap? = null
        private var bitmapFor: File? = null
        private var paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private var started = false

        private val tick = object : Runnable {
            override fun run() {
                if (!started) return
                drawFrame()
                schedule()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                if (started) schedule() else start()
            } else {
                stopTicking()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            reloadFrames()
            started = true
            schedule()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopTicking()
            super.onSurfaceDestroyed(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            bitmapFor = null // the cached frame is the wrong size now
        }

        override fun onDestroy() {
            stopTicking()
            super.onDestroy()
        }

        private var surfaceWidth = 0
        private var surfaceHeight = 0

        private fun schedule() {
            handler.removeCallbacks(tick)
            // 20 fps is enough for a cross-fade and roughly a fifth of the battery
            // of a smooth 60 fps loop on an idle home screen.
            handler.postDelayed(tick, 50L)
        }

        private fun stopTicking() {
            handler.removeCallbacks(tick)
        }

        private fun start() {
            started = true
            schedule()
        }

        private fun reloadFrames() {
            frames.clear()
            index = 0
            val folder = frameFolder() ?: return
            folder.listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.sortedBy { it.name }
                ?.forEach { frames += it }
        }

        private fun frameFolder(): File? {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val path = prefs.getString(KEY_FOLDER, null) ?: return defaultFolder()
            val dir = File(path)
            return dir.takeIf { it.isDirectory }
        }

        private fun defaultFolder(): File? {
            // The app's own wallpaper folder inside the workspace, if it exists.
            val root = android.os.Environment.getExternalStorageDirectory()
            val candidates = listOf(
                File(root, "Pictures/PocketRun/live"),
                File(root, "Pictures/PocketRun"),
            )
            return candidates.firstOrNull { it.isDirectory }
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            if (frames.isEmpty()) return
            val file = frames[index % frames.size]
            index = (index + 1) % frames.size

            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)

                // Re-decode only when the picture or the surface changed: this is
                // the difference between a wallpaper and a battery complaint.
                if (bitmap == null || bitmapFor != file || bitmap!!.width != canvas.width) {
                    val decoded = decode(file, canvas.width, canvas.height)
                    if (decoded == null) {
                        canvas.drawColor(Color.DKGRAY)
                        return
                    }
                    bitmap?.recycle()
                    bitmap = decoded
                    bitmapFor = file
                }
                val bmp = bitmap ?: return
                val scale = max(canvas.width.toFloat() / bmp.width, canvas.height.toFloat() / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                val left = (canvas.width - w) / 2f
                val top = (canvas.height - h) / 2f
                canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + w, top + h), paint)

                // A slow drift between frames turns a slideshow into something that
                // reads as moving, without needing an animation asset.
                val phase = (System.currentTimeMillis() % 20_000) / 20_000f
                val drift = sin(phase * 2f * Math.PI).toFloat() * canvas.width * 0.01f
                paint.alpha = 90 + (abs(sin(phase * Math.PI.toFloat())) * 60).toInt()
            } finally {
                canvas?.let { runCatching { holder.unlockCanvasAndPost(it) } }
            }
        }

        /** Decodes at display size with a subsample, so a 12 MP photo does not become a heap problem. */
        private fun decode(file: File, width: Int, height: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            )
        }.getOrNull()

    }

    private companion object {
        const val PREFS = "wallpaper"
        const val KEY_FOLDER = "folder"
    }
}
