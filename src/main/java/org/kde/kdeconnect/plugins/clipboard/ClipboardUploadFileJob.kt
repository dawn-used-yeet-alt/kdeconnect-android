/*
 * SPDX-FileCopyrightText: 2025 Hemant Verma <hemantioriverma@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.content.Context
import android.net.Uri
import android.util.Log
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.FilesHelper
import org.kde.kdeconnect.plugins.share.ShareJob

/**
 * A [ShareJob] which sends a single file to be put on the remote device's clipboard.
 *
 * Unlike [org.kde.kdeconnect.plugins.share.CompositeUploadFileJob] this does not show a
 * notification: clipboard transfers are expected to be fast and unobtrusive.
 */
class ClipboardUploadFileJob(
    device: Device,
    private val context: Context,
    private val uri: Uri,
    private val mimeType: String?,
    onFinished: (ShareJob) -> Unit,
) : ShareJob(device, onFinished) {

    @Volatile
    private var currentPacket: NetworkPacket? = null

    override fun run() {
        try {
            val packet = FilesHelper.uriToNetworkPacket(context, uri, ClipboardPlugin.PACKET_TYPE_CLIPBOARD_FILE)
                ?: throw RuntimeException("Could not read clipboard file: $uri")
            currentPacket = packet

            if (packet.payload?.inputStream == null) {
                throw RuntimeException("Could not open clipboard file: $uri")
            }
            if (isCancelled) {
                return
            }

            val filename = packet.getStringOrNull("filename")?.takeIf { it.isNotBlank() }
                ?: "clipboard-${System.currentTimeMillis()}"
            val fileMimeType = mimeType?.takeIf { it.isNotBlank() }
                ?: FilesHelper.getMimeTypeFromFile(filename)

            packet["filename"] = FilesHelper.ensureFileExtension(filename, fileMimeType)
            packet["mimeType"] = fileMimeType

            // sendPayloadFromSameThread = true so this blocks until the payload has been sent.
            if (!device.sendPacketBlocking(packet, sendPacketStatusCallback, true)) {
                throw RuntimeException("Sending clipboard file failed")
            }
        } catch (e: Exception) {
            if (isCancelled) {
                Log.d(TAG, "Cancelled sending clipboard file", e)
            } else {
                Log.e(TAG, "Error sending clipboard file", e)
            }
        } finally {
            currentPacket?.payload?.close()
        }
    }

    override fun cancel() {
        super.cancel()
        currentPacket?.cancel()
    }

    private val sendPacketStatusCallback = object : Device.SendPacketStatusCallback() {
        override fun onSuccess() {}

        override fun onFailure(e: Throwable) {
            // sendPacketBlocking() returns false and run() reports the error.
        }
    }

    private companion object {
        const val TAG = "ClipboardUploadFileJob"
    }
}
