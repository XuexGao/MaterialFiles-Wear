/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager2.widget.ViewPager2

/**
 * A [ViewPager2] that lets the current page consume horizontal scrolls while it is zoomed in, so
 * that the image is panned instead of the page being switched. Only after the image has reached
 * its edge does further swiping switch to another page.
 */
class ImageViewerViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewPager2(context, attrs) {
    /**
     * Set by the owner to ask whether the page currently displayed can still scroll itself by
     * [direction] (one of [android.view.View]#FOCUS_LEFT/-1 or FOCUS_RIGHT/+1), ie. whether it is
     * zoomed in and hasn't reached that edge yet.
     */
    var canCurrentPageScrollHorizontally: ((direction: Int) -> Boolean)? = null

    private var downX = 0f

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = motionEvent.x
            MotionEvent.ACTION_MOVE -> {
                val canScroll = canCurrentPageScrollHorizontally
                // Finger moved right means the image should pan right, revealing its left side.
                val direction = if (motionEvent.x > downX) -1 else 1
                if (canScroll?.invoke(direction) == true) {
                    return false
                }
            }
        }
        return super.onInterceptTouchEvent(motionEvent)
    }
}
