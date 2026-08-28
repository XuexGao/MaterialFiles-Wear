/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.video

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import java8.nio.file.Path
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
 * danmaku and no subtitle tracks, no stream qualities or pages. Only what is needed to play a
 * local video file on a watch is kept: a fit-to-screen texture, an app bar with the file name,
 * a progress bar and play/pause, ±10 seconds and volume buttons.
 */
class VideoPlayerActivity : AppActivity() {

    private lateinit var uri: Uri

    private lateinit var root: View
    private lateinit var appBarLayout: View
    private lateinit var textureView: TextureView
    private lateinit var loadingView: ProgressBar
    private lateinit var controlsView: View
    private lateinit var positionView: TextView
    private lateinit var durationView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: ImageButton

    private var parcelFileDescriptor: android.os.ParcelFileDescriptor? = null
    private var player: IjkMediaPlayer? = null
    private var surface: Surface? = null

    private var prepared = false

    private var resumePosition = 0L
    private var resumeByUser = false

    private var controlsVisible = true

    /** User-requested clockwise rotation of the picture, in degrees (0/90/180/270). */
    private var videoRotationDegrees = 0f

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
            // The window background flashes otherwise; the app bar covers the content anyway.
            it.setDisplayShowTitleEnabled(true)
            it.title = title
            it.setDisplayHomeAsUpEnabled(true)
        }

        if (savedInstanceState != null) {
            resumePosition = savedInstanceState.getLong(EXTRA_POSITION)
            resumeByUser = false
        }

        root.setOnClickListener { toggleControls() }
        playPauseButton.setOnClickListener { togglePlayPause() }
        findViewById<TextView>(R.id.rewind).setOnClickListener { seekBy(-SEEK_STEP_MS) }
        findViewById<TextView>(R.id.forward).setOnClickListener { seekBy(SEEK_STEP_MS) }
        findViewById<ImageButton>(R.id.volume_down).setOnClickListener {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )
            scheduleControlsHide()
        }
        findViewById<ImageButton>(R.id.volume_up).setOnClickListener {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )
            scheduleControlsHide()
        }
        findViewById<ImageButton>(R.id.rotate).setOnClickListener {
            videoRotationDegrees = (videoRotationDegrees + 90f) % 360f
            applyVideoScalingWhenLaidOut()
            scheduleControlsHide()
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

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surface?.release()
                surface = Surface(st)
                player?.setSurface(surface)
                applyVideoScalingWhenLaidOut()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                applyVideoScalingWhenLaidOut()
            }

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
        // The size is reported for the rotation-applied output of hardware-decoded streams.
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
     * A user-requested rotation re-fits the view to the rotated proportions and turns the view
     * around its center, so a quarter turn fills the screen instead of spilling out of it.
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
        val quarterTurns = (videoRotationDegrees / 90f).toInt() % 4
        val contentWidth = if (quarterTurns % 2 == 1) videoHeight else videoWidth
        val contentHeight = if (quarterTurns % 2 == 1) videoWidth else videoHeight
        // Contain-fit: either the video matches the screen height at a smaller width, or the
        // screen width at a smaller height.
        val widthAtScreenHeight = contentWidth * screenHeight / contentHeight
        val heightAtScreenWidth = contentHeight * screenWidth / contentWidth
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
        textureView.rotation = videoRotationDegrees
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
        private const val SEEK_STEP_MS = 10000L

        fun createIntent(context: Context, path: Path, mimeType: MimeType): Intent =
            path.fileProviderUri.createViewIntent(mimeType)
                .setClass(context, VideoPlayerActivity::class.java)
                .apply { extraPath = path }
    }
}
