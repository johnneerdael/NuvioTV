package com.nuvio.tv.core.player

import android.content.Context
import android.content.Intent
import com.nuvio.tv.ui.screens.player.LibVlcPlayerActivity

object LibVlcPlayerLauncher {
    fun launch(
        context: Context,
        streamUrl: String,
        title: String?
    ): Boolean {
        if (streamUrl.isBlank()) return false
        val intent = Intent(context, LibVlcPlayerActivity::class.java).apply {
            putExtra(LibVlcPlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(LibVlcPlayerActivity.EXTRA_TITLE, title)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
        return true
    }
}
