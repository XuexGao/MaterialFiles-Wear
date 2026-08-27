/*
 * Copyright (c) 2025 XuexGao <MaterialFiles-Wear>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.tools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.apk.ApkExtractActivity
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.ftpserver.FtpServerActivity
import me.zhanghai.android.files.databinding.ToolsListItemBinding
import me.zhanghai.android.files.util.startActivitySafe

class ToolsListActivity : AppActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ToolsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tools_list)
        recyclerView = findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ToolsAdapter(this) { tool ->
            when (tool) {
                is Tool.FtpServer -> startActivitySafe(Intent(this, FtpServerActivity::class.java))
                is Tool.ApkExtract -> startActivitySafe(Intent(this, ApkExtractActivity::class.java))
            }
        }
        recyclerView.adapter = adapter
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from != to) {
                    adapter.move(from, to)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.7f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                // TODO: persist order when more tools exist
            }
        })
        touchHelper.attachToRecyclerView(recyclerView)
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
}

private sealed interface Tool {
    data class FtpServer(val name: String, val description: String) : Tool
    data class ApkExtract(val name: String, val description: String) : Tool
}

private class ToolsAdapter(
    private val context: Context,
    private val onToolClick: (Tool) -> Unit
) : RecyclerView.Adapter<ToolsAdapter.ToolViewHolder>() {
    private val items = mutableListOf<Tool>(
        Tool.FtpServer(
            name = context.getString(R.string.navigation_ftp_server),
            description = context.getString(R.string.settings_tools_ftp_summary)
        ),
        Tool.ApkExtract(
            name = context.getString(R.string.navigation_apk_extract),
            description = context.getString(R.string.settings_tools_ftp_summary)
        )
    )

    override fun getItemId(position: Int): Long = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ToolViewHolder {
        val binding = ToolsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ToolViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ToolViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun move(from: Int, to: Int) {
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    inner class ToolViewHolder(private val binding: ToolsListItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onToolClick(items[position])
                }
            }
        }

        fun bind(tool: Tool) {
            binding.title.text = when (tool) {
                is Tool.FtpServer -> tool.name
                is Tool.ApkExtract -> tool.name
            }
            binding.summary.text = when (tool) {
                is Tool.FtpServer -> tool.description
                is Tool.ApkExtract -> tool.description
            }
            binding.icon.setImageResource(when (tool) { is Tool.FtpServer -> R.drawable.shared_directory_icon_white_24dp is Tool.ApkExtract -> R.drawable.file_apk_icon })
        }
    }
}
