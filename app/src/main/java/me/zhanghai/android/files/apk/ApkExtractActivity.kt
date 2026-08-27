/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.apk.ui.ApkListFragment
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.ApkExtractActivityBinding

class ApkExtractActivity : AppActivity() {
    private lateinit var binding: ApkExtractActivityBinding
    private var searchItem: MenuItem? = null
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ApkExtractActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.apk_extract, menu)
        searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                search(query)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300)
                    search(newText)
                }
                return true
            }
        })
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchJob?.cancel()
                search("")
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun search(query: String) {
        val currentItem = binding.viewPager.currentItem
        val tag = "f$currentItem"
        val fragment = supportFragmentManager.findFragmentByTag(tag)
        if (fragment is ApkListFragment && fragment.isAdded) {
            fragment.filter(query)
        }
    }

    private class ApkPagerAdapter(activity: AppActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment =
            if (position == 0) ApkListFragment.newInstance(false) else ApkListFragment.newInstance(true)
    }
}
