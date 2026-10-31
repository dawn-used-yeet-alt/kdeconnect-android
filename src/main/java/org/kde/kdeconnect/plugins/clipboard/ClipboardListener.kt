/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 * SPDX-FileCopyrightText: 2025 Hemant Verma <hemantioriverma@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect_tp.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardListener {
    enum class ClipboardContentType {
        Text,
        Password,
    }

    interface ClipboardObserver {
        fun clipboardChanged(content: String, contentType: ClipboardContentType) {}

        fun clipboardFileChanged(uri: Uri, mimeType: String?, contentType: ClipboardContentType) {}
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()

    private val context: Context
    var currentContent: String? = null
        private set
    var currentUri: Uri? = null
        private set
    var currentMimeType: String? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set

    private lateinit var cm: ClipboardManager

    private constructor(ctx: Context) {
        context = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            cm = context.getSystemService<ClipboardManager>()!!
            cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ClipboardPlugin.canSyncAutomatically(context)) {
            ThreadHelper.runOnNewThread {
                try {
                    val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                    // Listen only ClipboardService errors after now
                    val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
                    val process = Runtime.getRuntime().exec(arrayOf<String>("logcat", "-T", timeStamp, logcatFilter, "*:S"))
                    val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                    bufferedReader.forEachLine { line ->
                        if (line.contains(BuildConfig.APPLICATION_ID)) {
                            context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    fun onClipboardChanged() {
        try {
            val clip = cm.primaryClip ?: return
            val item = clip.getItemAt(0)
            val contentType = detectContentType(clip)
            val uri = item.uri

            // coerceToText() returns an empty string for items which only contain a URI, so
            // check for a URI first. If the item also has a text representation, prefer that.
            if (item.text == null && uri != null) {
                val mimeType = if (clip.description.mimeTypeCount > 0) {
                    clip.description.getMimeType(0)
                } else {
                    null
                }
                setCurrentFile(uri, mimeType, contentType)
            } else {
                setCurrentText(item.coerceToText(context).toString(), contentType)
            }
        } catch (_: Exception) {
            //Probably clipboard was not text
        }
    }

    fun setText(text: String?) {
        if (this::cm.isInitialized) {
            if (text == currentContent && currentUri == null) {
                return
            }
            updateTimestamp = System.currentTimeMillis()
            currentContent = text
            currentUri = null
            currentMimeType = null
            currentContentType = ClipboardContentType.Text
            cm.setPrimaryClip(ClipData.newPlainText(null, text))
        }
    }

    fun setFile(uri: Uri, mimeType: String?) {
        if (this::cm.isInitialized) {
            if (uri == currentUri && currentContent == null) {
                return
            }
            updateTimestamp = System.currentTimeMillis()
            currentContent = null
            currentUri = uri
            currentMimeType = mimeType
            currentContentType = ClipboardContentType.Text
            cm.setPrimaryClip(ClipData.newUri(context.contentResolver, null, uri))
        }
    }

    private fun setCurrentText(content: String, contentType: ClipboardContentType) {
        if (content == currentContent && currentUri == null && contentType == currentContentType) {
            return
        }
        updateTimestamp = System.currentTimeMillis()
        currentContent = content
        currentUri = null
        currentMimeType = null
        currentContentType = contentType

        for (observer in observers.toList()) {
            observer.clipboardChanged(content, contentType)
        }
    }

    private fun setCurrentFile(uri: Uri, mimeType: String?, contentType: ClipboardContentType) {
        if (uri == currentUri && currentContent == null && contentType == currentContentType) {
            return
        }
        updateTimestamp = System.currentTimeMillis()
        currentContent = null
        currentUri = uri
        currentMimeType = mimeType
        currentContentType = contentType

        for (observer in observers.toList()) {
            observer.clipboardFileChanged(uri, mimeType, contentType)
        }
    }

    companion object {
        private var _instance: ClipboardListener? = null

        fun instance(context: Context): ClipboardListener {
            // FIXME: The _instance we return won't be completely initialized yet since initialization happens on a new thread (why?)
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }

        fun detectContentType(clip: ClipData?): ClipboardContentType {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return ClipboardContentType.Text
            }
            if (clip?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return ClipboardContentType.Password
            }
            return ClipboardContentType.Text
        }
    }
}
