/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.ApkExtractActivityBinding

class ApkExtractActivity : AppActivity() {
    private lateinit var binding: ApkExtractActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ApkExtractActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = ApkPagerAdapter(this)
        binding.viewPager.adapter = adapter
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = if (position == 0) {
                getString(R.string.apk_extract_tab_user)
            } else {
                getString(R.string.apk_extract_tab_system)
            }
        }.attach()
    }

    private class ApkPagerAdapter(activity: AppActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment =
            if (position == 0) ApkListFragment.newInstance(false) else ApkListFragment.newInstance(true)
    }
}
