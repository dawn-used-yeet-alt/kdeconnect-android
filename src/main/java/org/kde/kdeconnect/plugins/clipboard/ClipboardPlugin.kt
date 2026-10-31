/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2025 Hemant Verma <hemantioriverma@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener.ClipboardObserver
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class ClipboardPlugin : Plugin() {
    private var uploadFileJob: ClipboardUploadFileJob? = null
    private var pendingClipboardFile: Pair<Uri, String?>? = null
    private var receiveFileJob: ClipboardReceiveFileJob? = null

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_clipboard)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_clipboard_desc)

    override val hasSettings: Boolean = true

    override val settingsScope = SettingsScope.PER_DEVICE

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment {
        return PluginSettingsFragment.newInstance(pluginKey, device.deviceId, R.xml.clipboardplugin_preferences)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        val content = np.getString("content")
        when (np.type) {
            (PACKET_TYPE_CLIPBOARD) -> {
                ClipboardListener.instance(context).setText(content)
                return true
            }
            (PACKET_TYPE_CLIPBOARD_FILE) -> {
                receiveClipboardFile(np)
                return true
            }
            (PACKET_TYPE_CLIPBOARD_CONNECT) -> {
                val packetTime = np.getLong("timestamp")
                // If the packetTime is 0, it means the timestamp is unknown (so do nothing).
                if (packetTime == 0L || packetTime < ClipboardListener.instance(context).updateTimestamp) {
                    return false
                }
                if ("content" in np) { // change clipboard if content is in NetworkPacket
                    ClipboardListener.instance(context).setText(content)
                }
                return true
            }
            else -> throw UnsupportedOperationException("Unknown packet type: ${np.type}")
        }
    }

    private val observer: ClipboardObserver = object : ClipboardObserver {
        override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) {
            if (shouldSkip(contentType)) {
                return
            }
            propagateClipboard(content)
        }

        override fun clipboardFileChanged(
            uri: Uri,
            mimeType: String?,
            contentType: ClipboardListener.ClipboardContentType,
        ) {
            if (shouldSkip(contentType)) {
                return
            }
            propagateClipboard(uri, mimeType)
        }
    }

    private fun shouldSkip(contentType: ClipboardListener.ClipboardContentType): Boolean {
        return contentType == ClipboardListener.ClipboardContentType.Password &&
            preferences.getBoolean(
                context.getString(R.string.clipboard_preference_key_skip_sensitive),
                false,
            )
    }

    @VisibleForTesting
    fun propagateClipboard(content: String) {
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD)
        np["content"] = content
        device.sendPacket(np)
    }

    @VisibleForTesting
    @Synchronized
    fun propagateClipboard(uri: Uri, mimeType: String?) {
        // Clipboard changes can happen in quick succession (for example when taking several
        // screenshots). Only the latest item is interesting, so keep it until the running
        // transfer has finished instead of sending multiple files at once.
        pendingClipboardFile = uri to mimeType
        startPendingClipboardUpload()
    }

    @Synchronized
    private fun startPendingClipboardUpload() {
        if (uploadFileJob != null) {
            return
        }

        val (uri, mimeType) = pendingClipboardFile ?: return
        pendingClipboardFile = null

        val job = ClipboardUploadFileJob(device, context, uri, mimeType) { finishedJob ->
            synchronized(this) {
                if (uploadFileJob === finishedJob) {
                    uploadFileJob = null
                    startPendingClipboardUpload()
                }
            }
        }
        uploadFileJob = job
        job.start()
    }

    @Synchronized
    private fun receiveClipboardFile(np: NetworkPacket) {
        if (receiveFileJob != null) {
            Log.w(TAG, "Ignoring clipboard file while another one is being received")
            // The payload socket is already connected at this point, so close it to let the
            // sender know that this transfer was not accepted.
            np.payload?.close()
            return
        }

        val job = ClipboardReceiveFileJob(device, np) { finishedJob ->
            synchronized(this) {
                if (receiveFileJob === finishedJob) {
                    receiveFileJob = null
                }
            }
            (finishedJob as ClipboardReceiveFileJob).receivedFile?.let { receivedFile ->
                ClipboardListener.instance(context).setFile(receivedFile.uri, receivedFile.mimeType)
            }
        }
        receiveFileJob = job
        job.start()
    }

    private fun sendConnectPacket() {
        val content = ClipboardListener.instance(context).currentContent ?: return // Send clipboard only if it had been initialized
        if (shouldSkip(ClipboardListener.instance(context).currentContentType)) {
            return
        }
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD_CONNECT)
        val timestamp = ClipboardListener.instance(context).updateTimestamp
        np["timestamp"] = timestamp
        np["content"] = content
        device.sendPacket(np)
    }

    override fun onCreate() {
        ClipboardListener.instance(context).registerObserver(observer)
        sendConnectPacket()
    }

    override fun onDestroy() {
        ClipboardListener.instance(context).removeObserver(observer)
        synchronized(this) {
            pendingClipboardFile = null
            uploadFileJob?.cancel()
            receiveFileJob?.cancel()
        }
    }

    override val supportedPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_CLIPBOARD,
        PACKET_TYPE_CLIPBOARD_FILE,
        PACKET_TYPE_CLIPBOARD_CONNECT,
    )

    override val outgoingPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_CLIPBOARD,
        PACKET_TYPE_CLIPBOARD_FILE,
        PACKET_TYPE_CLIPBOARD_CONNECT,
    )

    override fun getUiButtons(): List<PluginUiButton> {
        return if (!canSyncAutomatically(context)) {
            listOf(PluginUiButton(context.getString(R.string.send_clipboard), R.drawable.ic_baseline_content_paste_24) { _: Activity? ->
                userInitiatedSendClipboard()
            })
        } else {
            emptyList()
        }
    }

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> {
        return if (canSyncAutomatically(context)) {
            // If automatic sync is supported, add the manual action less prominently, as a menu entry instead of a button.
            listOf(PluginUiMenuEntry(context.getString(R.string.send_clipboard)) { _: Activity? ->
                userInitiatedSendClipboard()
            })
        } else {
            emptyList()
        }
    }

    private fun userInitiatedSendClipboard() {
        if (!isDeviceInitialized) {
            return
        }

        val clipboardManager = context.getSystemService<ClipboardManager>() ?: return
        val clip = clipboardManager.primaryClip ?: return
        val item: ClipData.Item = clip.getItemAt(0)
        val uri = item.uri

        if (item.text == null && uri != null) {
            val mimeType = if (clip.description.mimeTypeCount > 0) {
                clip.description.getMimeType(0)
            } else {
                null
            }
            propagateClipboard(uri, mimeType)
        } else {
            // Don't check if the content is sensitive, just send it
            propagateClipboard(item.coerceToText(context).toString())
        }
        Toast.makeText(context, R.string.pref_plugin_clipboard_sent, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "ClipboardPlugin"

        /**
         * Packet containing just clipboard contents, sent when a device updates its clipboard.
         *
         *
         * The body should look like so:
         * {
         * "content": "password"
         * }
         */
        private const val PACKET_TYPE_CLIPBOARD = "kdeconnect.clipboard"

        /**
         * Packet containing a single file which should be put on the clipboard.
         *
         * The packet has a payload and a 'filename' property:
         * {
         * "filename": "image.png",
         * "mimeType": "image/png"
         * }
         *
         * 'mimeType' is optional; peers that don't know it can derive the type from the filename.
         */
        internal const val PACKET_TYPE_CLIPBOARD_FILE = "kdeconnect.clipboard.file"

        /**
         * Packet containing clipboard contents and a timestamp that the contents were last updated, sent
         * on first connection
         *
         *
         * The timestamp is milliseconds since epoch. It can be 0, which indicates that the clipboard
         * update time is currently unknown.
         *
         *
         * The body should look like so:
         * {
         * "timestamp": 542904563213,
         * "content": "password"
         * }
         */
        private const val PACKET_TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"

        fun canSyncAutomatically(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return true
            }
            // Check if hack to read clipboard changes from logs is enabled
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        }

    }
}
