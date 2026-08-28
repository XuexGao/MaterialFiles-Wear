/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Size
import com.davemorrissey.labs.subscaleview.ImageSource
import com.github.chrisbanes.photoview.PhotoView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.coil.fadeIn
import me.zhanghai.android.files.databinding.ImageViewerItemBinding
import kotlin.math.abs
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.shortAnimTime
import kotlin.math.max

class ImageViewerAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val listener: (View) -> Unit
) : SimpleAdapter<Path, ImageViewerAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ImageViewerItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = getItem(position)
        val binding = holder.binding
        // Tapping anywhere toggles the system UI, including areas not covered by the image itself.
        binding.root.setOnClickListener { listener(it) }
        binding.progress.setOnClickListener(listener)
        binding.errorText.setOnClickListener(listener)
        // Single taps anywhere on the image toggle the system UI; double tapping and dragging
        // up/down adjusts the zoom continuously, matching the quick scale gesture of
        // SubsamplingScaleImageView.
        binding.image.installImageGestures(
            onTitleTap = { listener(binding.image) },
            canPan = { direction -> binding.image.canScrollHorizontally(direction) }
        )
        // The large image has its own double tap zoom, and single taps toggle the system UI.
        // SubsamplingScaleImageView consumes touch events itself, so a plain OnClickListener would
        // never fire; a detector watching the events is used instead.
        binding.largeImage.installPanInterceptor(
            onSingleTap = { listener(binding.largeImage) }
        ) { binding.largeImage.canScrollHorizontally(it) }
        loadImage(binding, path)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)

        val binding = holder.binding
        binding.image.dispose()
        binding.largeImage.recycle()
    }

    /**
     * Handles all gestures of a PhotoView image with the attacher's own, standard gestures, the
     * same way the SubsamplingScaleImageView handles large images: pinch zooming, double tap
     * zooming and flinging. Single taps toggle the system UI through [onTitleTap], and while the
     * image can still pan itself horizontally, the pager is asked not to intercept so that pages
     * are only switched at its edges.
     */
    private fun PhotoView.installImageGestures(
        onTitleTap: () -> Unit,
        canPan: (direction: Int) -> Boolean
    ) {
        val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(motionEvent: MotionEvent): Boolean {
                    onTitleTap()
                    return true
                }
            }
        )

        var lastX = 0f
        var disallowed = false

        setOnTouchListener { view, motionEvent ->
            // Installing this listener replaced the attacher's own one, so its events have to be
            // forwarded explicitly; otherwise pinch zooming, double tap zooming, panning and
            // flinging would all be dead.
            gestureDetector.onTouchEvent(motionEvent)
            attacher.onTouch(view, motionEvent)
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = motionEvent.x
                    disallowed = false
                }
                MotionEvent.ACTION_MOVE -> {
                    // Finger moved right means the image should pan right, revealing its
                    // left side.
                    val direction = if (motionEvent.x > lastX) -1 else 1
                    lastX = motionEvent.x
                    if (!disallowed && canPan(direction)) {
                        disallowed = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (disallowed) {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        disallowed = false
                    }
                }
            }
            // Consuming everything keeps the event stream consistent for the attacher; page
            // switching is negotiated through requestDisallowInterceptTouchEvent above.
            true
        }
    }

    /**
     * Single taps on the view toggle the system UI through [onSingleTap]; the toggle is delayed
     * by the double tap window so that the view's own double tap zoom is not interrupted, even
     * when the double tap is slightly slower than the system timeout.
     * While the view can still pan itself horizontally, the pager is asked not to intercept so
     * that pages are only switched once its edge has been reached.
     */
    private fun View.installPanInterceptor(
        onSingleTap: () -> Unit,
        canPan: (direction: Int) -> Boolean
    ) {
        val viewConfiguration = ViewConfiguration.get(context)
        val touchSlop = viewConfiguration.scaledTouchSlop
        val doubleTapWindow = ViewConfiguration.getDoubleTapTimeout() + 100L

        var lastTapUpAt = 0L
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var moved = false
        var disallowed = false

        val toggleTitle = Runnable { onSingleTap() }
        // A recycled or rebound view may still carry a pending title toggle.
        removeCallbacks(toggleTitle)

        setOnTouchListener { view, motionEvent ->
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = motionEvent.x
                    downY = motionEvent.y
                    lastX = motionEvent.x
                    moved = false
                    disallowed = false
                    // A second tap within the window is the view's own double tap zoom; cancel
                    // the pending title toggle of the first tap.
                    if (lastTapUpAt != 0L &&
                            SystemClock.uptimeMillis() - lastTapUpAt <= doubleTapWindow) {
                        view.removeCallbacks(toggleTitle)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(motionEvent.x - downX) > touchSlop ||
                                    abs(motionEvent.y - downY) > touchSlop)) {
                        moved = true
                    }
                    // Finger moved right means the image should pan right, revealing its left side.
                    val direction = if (motionEvent.x > lastX) -1 else 1
                    lastX = motionEvent.x
                    if (!disallowed && canPan(direction)) {
                        disallowed = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // Wait out the double tap window so that a slow double tap zoom doesn't
                        // end up toggling the title twice.
                        view.postDelayed(toggleTitle, doubleTapWindow)
                    }
                    lastTapUpAt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (disallowed) {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        disallowed = false
                    }
                }
            }
            // Never consume the event so that the view keeps handling gestures itself.
            false
        }
    }

    private fun loadImage(binding: ImageViewerItemBinding, path: Path) {
        binding.progress.fadeInUnsafe(true)
        binding.errorText.fadeOutUnsafe()
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
        lifecycleOwner.lifecycleScope.launch {
            val imageInfo = try {
                withContext(Dispatchers.IO) { path.loadImageInfo() }
            } catch (e: Exception) {
                e.printStackTrace()
                showError(binding, e)
                return@launch
            }
            loadImageWithInfo(binding, path, imageInfo)
        }
    }

    private fun Path.loadImageInfo(): ImageInfo {
        val attributes = readAttributes(BasicFileAttributes::class.java)
        val mimeType = AndroidFileTypeDetector.getMimeType(this, attributes).asMimeType()
        val bitmapOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        newInputStream().use { BitmapFactory.decodeStream(it, null, bitmapOptions) }
        return ImageInfo(
            attributes, bitmapOptions.outWidth, bitmapOptions.outHeight,
            bitmapOptions.outMimeType?.asMimeTypeOrNull() ?: mimeType
        )
    }

    private fun loadImageWithInfo(
        binding: ImageViewerItemBinding,
        path: Path,
        imageInfo: ImageInfo
    ) {
        if (!imageInfo.shouldUseLargeImageView) {
            binding.image.apply {
                isVisible = true
                // Don't allow zooming out below the fitted, full-image size.
                minimumScale = 1f
                load(path to imageInfo.attributes) {
                    size(Size.ORIGINAL)
                    fadeIn(context.shortAnimTime)
                    listener(
                        onSuccess = { _, _ -> binding.progress.fadeOutUnsafe() },
                        onError = { _, result -> showError(binding, result.throwable) }
                    )
                }
            }
        } else {
            binding.largeImage.apply {
                setDoubleTapZoomDuration(300)
                orientation = SubsamplingScaleImageView.ORIENTATION_USE_EXIF
                // Otherwise OnImageEventListener.onReady() is never called.
                isVisible = true
                alpha = 0f
                setOnImageEventListener(object : DefaultOnImageEventListener() {
                    override fun onReady() {
                        setDoubleTapZoomScale(binding.largeImage.cropScale)
                        binding.progress.fadeOutUnsafe()
                        binding.largeImage.fadeInUnsafe(true)
                    }

                    override fun onImageLoadError(e: Exception) {
                        e.printStackTrace()
                        showError(binding, e)
                    }
                })
                setImageRestoringSavedState(ImageSource.uri(path.fileProviderUri))
            }
        }
    }

    private val ImageInfo.shouldUseLargeImageView: Boolean
        get() {
            // See BitmapFactory.cpp encodedFormatToString()
            if (mimeType == MimeType.IMAGE_GIF) {
                return false
            }
            if (width <= 0 || height <= 0) {
                return false
            }
            // 4 bytes per pixel for ARGB_8888.
            if (width * height * 4 > MAX_BITMAP_SIZE) {
                return true
            }
            if (width > 2048 || height > 2048) {
                val ratio = width.toFloat() / height
                if (ratio < 0.5 || ratio > 2) {
                    return true
                }
            }
            return false
        }

    private val SubsamplingScaleImageView.cropScale: Float
        get() {
            val viewWidth = (width - paddingLeft - paddingRight)
            val viewHeight = (height - paddingTop - paddingBottom)
            val orientation = appliedOrientation
            val rotated90Or270 = orientation == SubsamplingScaleImageView.ORIENTATION_90
                || orientation == SubsamplingScaleImageView.ORIENTATION_270
            val imageWidth = if (rotated90Or270) sHeight else sWidth
            val imageHeight = if (rotated90Or270) sWidth else sHeight
            return max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        }

    private fun showError(binding: ImageViewerItemBinding, throwable: Throwable) {
        binding.progress.fadeOutUnsafe()
        binding.errorText.text = throwable.toString()
        binding.errorText.fadeInUnsafe(true)
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
    }

    companion object {
        // @see android.graphics.RecordingCanvas#MAX_BITMAP_SIZE
        private const val MAX_BITMAP_SIZE = 100 * 1024 * 1024
    }

    class ViewHolder(val binding: ImageViewerItemBinding) : RecyclerView.ViewHolder(binding.root)

    private class ImageInfo(
        val attributes: BasicFileAttributes,
        val width: Int,
        val height: Int,
        val mimeType: MimeType
    )
}
