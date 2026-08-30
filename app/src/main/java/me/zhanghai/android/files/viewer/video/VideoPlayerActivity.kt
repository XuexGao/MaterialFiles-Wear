/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import java8.nio.file.Path
import kotlin.math.abs
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast

/**
 * A compact local video player, integrated from BiliTerminal's IjkMediaPlayer-based player
 * (https://github.com/PianoEthan/BiliTerminal) with its Bilibili-specific parts removed: no
 * danmaku and no subtitle tracks, no stream qualities or pages. Kept from it are the engine
 * options, the fit-to-screen video sizing, the landscape/portrait rotate button, the volume
 * percentage overlay and the pinch-to-zoom gestures with double tap seek and reset.
 */
class VideoPlayerActivity : AppActivity() {

    private lateinit var uri: Uri

    private lateinit var root: View
    private lateinit var appBarLayout: View
    private lateinit var textureView: TextureView
    private lateinit var loadingView: ProgressBar
    private lateinit var controlsView: View
    private lateinit var volumeLabel: TextView
    private lateinit var positionView: TextView
    private lateinit var durationView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: ImageButton

    private var parcelFileDescriptor: android.os.ParcelFileDescriptor? = null
    private var player: IjkMediaPlayer? = null
    private var surface: Surface? = null

    private var prepared = false
    private var landscape = false

    private var resumePosition = 0L
    private var resumeByUser = false

    private var controlsVisible = true

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private val hideControlsRunnable = Runnable {
        if (isPlaying) {
            setControlsVisible(false)
        }
    }

    private val hideVolumeRunnable = Runnable {
        volumeLabel.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intentUri = intent.data
        if (intentUri == null) {
            finish()
            return
        }
        uri = intentUri

        setContentView(R.layout.video_player_activity)

        root = findViewById(R.id.root)
        appBarLayout = findViewById(R.id.appBarLayout)
        textureView = findViewById(R.id.texture)
        loadingView = findViewById(R.id.loading)
        controlsView = findViewById(R.id.controls)
        volumeLabel = findViewById(R.id.volume_label)
        positionView = findViewById(R.id.position)
        durationView = findViewById(R.id.duration)
        seekBar = findViewById(R.id.seek_bar)
        playPauseButton = findViewById(R.id.play_pause)

        val path: Path? = intent.extraPath
        val title = path?.fileName?.toString()
            ?: uri.lastPathSegment
            ?: getString(R.string.video_player_title)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.let {
            it.title = title
            it.setDisplayHomeAsUpEnabled(true)
        }

        if (savedInstanceState != null) {
            resumePosition = savedInstanceState.getLong(EXTRA_POSITION)
            resumeByUser = false
        }

        playPauseButton.setOnClickListener { togglePlayPause() }
        findViewById<ImageButton>(R.id.rewind).setOnClickListener { seekBy(-SEEK_STEP_MS) }
        findViewById<ImageButton>(R.id.forward).setOnClickListener { seekBy(SEEK_STEP_MS) }
        findViewById<ImageButton>(R.id.volume_down).setOnClickListener { changeVolume(false) }
        findViewById<ImageButton>(R.id.volume_up).setOnClickListener { changeVolume(true) }
        findViewById<ImageButton>(R.id.rotate).setOnClickListener {
            // Like BiliTerminal's rotate button: switch between portrait and landscape watching;
            // the video is refitted to the new screen proportions in onConfigurationChanged().
            landscape = !landscape
            requestedOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.seekTo(progress.toLong())
                    updateProgress()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Refit the video whenever the screen proportions change.
        root.addOnLayoutChangeListener { _, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                applyVideoScaling()
            }
        }

        installVideoGestures()

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surface?.release()
                surface = Surface(st)
                player?.setSurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                player?.setSurface(null)
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        startPlayback()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyVideoScalingWhenLaidOut()
    }

    /**
     * The gestures of the video area, mirroring BiliTerminal's setVideoGestures():
     * * Pinching zooms the picture between 1x and 5x, and dragging pans it while zoomed in.
     * * Double tapping the left or right third seeks ten seconds back or forward.
     * * Double tapping the middle resets the zoom, or toggles playback when not zoomed in.
     * * A single tap toggles the controls.
     */
    private fun installVideoGestures() {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(motionEvent: MotionEvent): Boolean {
                    toggleControls()
                    return true
                }

                override fun onDoubleTap(motionEvent: MotionEvent): Boolean {
                    if (!prepared) {
                        return false
                    }
                    // Double tapping only resets the zoom; there is no play/pause or seeking
                    // on double tap because it conflicts with the zoom gestures.
                    resetVideoZoom()
                    return true
                }
            }
        )
        val scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val newScale = (textureView.scaleX * detector.scaleFactor).coerceIn(1f, MAX_SCALE)
                    textureView.scaleX = newScale
                    textureView.scaleY = newScale
                    clampVideoTranslation()
                    return true
                }
            }
        )

        var lastX = 0f
        var lastY = 0f

        root.setOnTouchListener { _, motionEvent ->
            gestureDetector.onTouchEvent(motionEvent)
            scaleGestureDetector.onTouchEvent(motionEvent)
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = motionEvent.x
                    lastY = motionEvent.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (textureView.scaleX > 1f && !scaleGestureDetector.isInProgress) {
                        val deltaX = motionEvent.x - lastX
                        val deltaY = motionEvent.y - lastY
                        if (abs(deltaX) > 1f || abs(deltaY) > 1f) {
                            textureView.translationX += deltaX
                            textureView.translationY += deltaY
                            clampVideoTranslation()
                            lastX = motionEvent.x
                            lastY = motionEvent.y
                        }
                    }
                }
            }
            true
        }
    }

    private fun clampVideoTranslation() {
        val maxTranslationX = textureView.width * (textureView.scaleX - 1f) / 2f
        val maxTranslationY = textureView.height * (textureView.scaleY - 1f) / 2f
        textureView.translationX =
            textureView.translationX.coerceIn(-maxTranslationX, maxTranslationX)
        textureView.translationY =
            textureView.translationY.coerceIn(-maxTranslationY, maxTranslationY)
    }

    private fun resetVideoZoom() {
        textureView.scaleX = 1f
        textureView.scaleY = 1f
        textureView.translationX = 0f
        textureView.translationY = 0f
    }

    private fun startPlayback() {
        IjkMediaPlayer.loadLibrariesOnce(null)

        val pfd = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(R.string.video_player_error)
            finish()
            return
        }
        parcelFileDescriptor = pfd ?: run {
            showToast(R.string.video_player_error)
            finish()
            return
        }

        val player = IjkMediaPlayer()
        this.player = player
        // The option set mirrors BiliTerminal's PlayerActivity for smooth playback on watches.
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 4)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2000000)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48)

        player.setOnPreparedListener { onPrepared(it) }
        player.setOnCompletionListener {
            finish()
        }
        player.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "Playback error what=$what extra=$extra")
            showToast(R.string.video_player_error)
            finish()
            true
        }
        player.setOnInfoListener { _, what, _ ->
            if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                loadingView.visibility = View.GONE
            }
            false
        }
        player.setOnVideoSizeChangedListener { _, _, _, _, _ ->
            applyVideoScalingWhenLaidOut()
        }

        surface?.let { player.setSurface(it) }

        try {
            player.setDataSource(parcelFileDescriptor!!.fileDescriptor)
            player.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(R.string.video_player_error)
            finish()
        }
    }

    private fun onPrepared(mp: IMediaPlayer) {
        prepared = true
        loadingView.visibility = View.GONE
        seekBar.max = mp.duration.toInt().coerceAtLeast(0)
        durationView.text = formatDuration(mp.duration)
        if (resumePosition > 0) {
            mp.seekTo(resumePosition)
        }
        applyVideoScalingWhenLaidOut()
        startOrResume()
    }

    /**
     * The video size and the screen size can become known in either order, so the video view is
     * resized whenever either side changes and deferred through the view tree when the activity
     * isn't laid out yet.
     */
    private fun applyVideoScalingWhenLaidOut() {
        if (root.width > 0 && root.height > 0) {
            applyVideoScaling()
        } else {
            root.post { applyVideoScaling() }
        }
    }

    /**
     * Sizes the texture view to the video's aspect ratio, exactly like BiliTerminal's
     * changeVideoSize(): the surface is never stretched, because the view itself takes on the
     * video's proportions within the screen instead of the texture being scaled with a matrix.
     * Any zoom or pan is reset, since the proportions changed underneath it.
     */
    private fun applyVideoScaling() {
        val player = player ?: return
        val videoWidth = player.videoWidth
        val videoHeight = player.videoHeight
        if (videoWidth == 0 || videoHeight == 0) {
            return
        }
        val screenWidth = root.width
        val screenHeight = root.height
        if (screenWidth == 0 || screenHeight == 0) {
            return
        }
        // Contain-fit: either the video matches the screen height at a smaller width, or the
        // screen width at a smaller height.
        val widthAtScreenHeight = videoWidth * screenHeight / videoHeight
        val heightAtScreenWidth = videoHeight * screenWidth / videoWidth
        val videoViewWidth: Int
        val videoViewHeight: Int
        if (widthAtScreenHeight <= screenWidth) {
            videoViewWidth = widthAtScreenHeight
            videoViewHeight = screenHeight
        } else {
            videoViewWidth = screenWidth
            videoViewHeight = heightAtScreenWidth
        }
        textureView.layoutParams = FrameLayout.LayoutParams(
            videoViewWidth, videoViewHeight, Gravity.CENTER
        )
        resetVideoZoom()
    }

    private fun togglePlayPause() {
        val player = player ?: return
        if (player.isPlaying) {
            pausePlayback()
        } else {
            startOrResume()
        }
    }

    private fun startOrResume() {
        val player = player ?: return
        if (!prepared) {
            return
        }
        requestAudioFocus()
        player.start()
        playPauseButton.setImageResource(R.drawable.video_player_pause)
        root.keepScreenOn = true
        handler.removeCallbacks(updateRunnable)
        updateProgress()
        handler.postDelayed(updateRunnable, PROGRESS_INTERVAL_MS)
        scheduleControlsHide()
    }

    private fun pausePlayback() {
        val player = player ?: return
        player.pause()
        playPauseButton.setImageResource(R.drawable.video_player_play)
        root.keepScreenOn = false
        abandonAudioFocus()
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        setControlsVisible(true)
    }

    private fun seekBy(deltaMs: Long) {
        val player = player ?: return
        if (!prepared) {
            return
        }
        val target = (player.currentPosition + deltaMs).coerceIn(0L, player.duration)
        player.seekTo(target)
        updateProgress()
        scheduleControlsHide()
    }

    private fun updateProgress() {
        val player = player ?: return
        if (!prepared) {
            return
        }
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.coerceAtLeast(0L)
        positionView.text = formatDuration(position)
        durationView.text = formatDuration(duration)
        if (!seekBar.isPressed) {
            seekBar.progress = position.toInt().coerceAtMost(seekBar.max)
        }
    }

    /**
     * Steps the media volume by one and shows the percentage like BiliTerminal's changeVolume(),
     * because watch systems don't display the volume HUD for these adjustments.
     */
    private fun changeVolume(add: Boolean) {
        var volumeNow = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumeNew = volumeNow + if (add) 1 else -1
        if (volumeNew in 0..volumeMax) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeNew, 0)
            volumeNow = volumeNew
        }
        volumeLabel.text =
            getString(R.string.video_player_volume_format, volumeNow * 100 / volumeMax)
        volumeLabel.visibility = View.VISIBLE
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, VOLUME_HIDE_DELAY_MS)
        scheduleControlsHide()
    }

    private fun toggleControls() {
        setControlsVisible(!controlsVisible)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        controlsView.visibility = if (visible) View.VISIBLE else View.GONE
        appBarLayout.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            scheduleControlsHide()
        } else {
            handler.removeCallbacks(hideControlsRunnable)
        }
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (isPlaying) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
        }
    }

    private val isPlaying: Boolean
        get() = prepared && player?.isPlaying == true

    private fun requestAudioFocus() {
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(
            audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        )
    }

    private fun abandonAudioFocus() {
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(audioFocusListener)
    }

    override fun onPause() {
        super.onPause()
        resumePosition = player?.currentPosition ?: 0L
        resumeByUser = isPlaying
        pausePlayback()
    }

    override fun onResume() {
        super.onResume()
        if (resumeByUser) {
            startOrResume()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(EXTRA_POSITION, player?.currentPosition ?: 0L)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.let { player ->
            try {
                player.stop()
            } catch (ignored: Exception) {}
            player.release()
        }
        player = null
        surface?.release()
        surface = null
        parcelFileDescriptor?.let { runCatching { it.close() } }
        parcelFileDescriptor = null
        abandonAudioFocus()
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val seconds = (totalSeconds % 60).toInt()
        val minutes = (totalSeconds / 60 % 60).toInt()
        val hours = (totalSeconds / 3600).toInt()
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        private val TAG = VideoPlayerActivity::class.java.simpleName
        private val EXTRA_POSITION = "${VideoPlayerActivity::class.java.name}.extra.POSITION"

        private const val PROGRESS_INTERVAL_MS = 500L
        private const val CONTROLS_HIDE_DELAY_MS = 3000L
        private const val VOLUME_HIDE_DELAY_MS = 3000L
        private const val SEEK_STEP_MS = 10000L
        private const val MAX_SCALE = 5f

        fun createIntent(context: Context, path: Path, mimeType: MimeType): Intent =
            path.fileProviderUri.createViewIntent(mimeType)
                .setClass(context, VideoPlayerActivity::class.java)
                .apply { extraPath = path }
    }
}
