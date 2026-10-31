/*
 * SPDX-FileCopyrightText: 2025 Hemant Verma <hemantioriverma@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.FilesHelper
import org.kde.kdeconnect.plugins.share.ShareJob
import org.kde.kdeconnect_tp.BuildConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A [ShareJob] which receives a single file and makes it available to be put on the clipboard.
 *
 * Files are stored in a cache directory, since they are only needed while the clipboard still
 * references them. The received file is exposed through the same [FileProvider] the share plugin
 * uses, so other apps can read it after it has been put on the clipboard.
 */
class ClipboardReceiveFileJob(
    device: Device,
    private val packet: NetworkPacket,
    onFinished: (ShareJob) -> Unit,
) : ShareJob(device, onFinished) {

    data class ReceivedFile(val uri: Uri, val mimeType: String?)

    @Volatile
    var receivedFile: ReceivedFile? = null
        private set

    override fun run() {
        var file: File? = null
        try {
            val packetMimeType = packet.getStringOrNull("mimeType")
                ?.takeIf { it.isNotBlank() && it != "*/*" }
            val originalFilename = packet.getStringOrNull("filename")?.takeIf { it.isNotBlank() }
            val mimeType = packetMimeType ?: FilesHelper.getMimeTypeFromFile(originalFilename)
            val filename = originalFilename ?: "clipboard-${System.currentTimeMillis()}"
            val filenameWithExtension = FilesHelper.ensureFileExtension(filename, mimeType)
                .let { if (it.substringAfterLast('.', "").isEmpty()) "$it.bin" else it }

            val directory = File(device.context.cacheDir, CLIPBOARD_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) {
                throw RuntimeException("Could not create clipboard directory: $directory")
            }

            val filenameToUse = FilesHelper.findValidNonExistingFileName(
                DocumentFile.fromFile(directory),
                filenameWithExtension,
            )
            file = File(directory, filenameToUse)

            val payload = packet.payload ?: throw RuntimeException("Clipboard file packet has no payload")
            val inputStream = payload.inputStream
                ?: throw RuntimeException("Clipboard file packet has no payload stream")

            BufferedOutputStream(file.outputStream()).use { outputStream ->
                receiveFile(inputStream, outputStream)
            }

            if (isCancelled) {
                file.delete()
                return
            }
            if (packet.payloadSize >= 0 && file.length() < packet.payloadSize) {
                throw RuntimeException(
                    "Failed to receive clipboard file: received ${file.length()} bytes, " +
                        "expected ${packet.payloadSize} bytes"
                )
            }

            pruneOldFiles(directory, file)

            val uri = FileProvider.getUriForFile(
                device.context,
                BuildConfig.APPLICATION_ID + ".fileprovider",
                file,
            )
            receivedFile = ReceivedFile(uri, mimeType)
        } catch (e: Exception) {
            file?.delete()
            if (isCancelled) {
                Log.d(TAG, "Cancelled receiving clipboard file", e)
            } else {
                Log.e(TAG, "Error receiving clipboard file", e)
            }
        } finally {
            packet.payload?.close()
        }
    }

    private fun receiveFile(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (!isCancelled) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            output.write(buffer, 0, count)
        }
        output.flush()
    }

    /**
     * Keep the clipboard directory from growing forever. The most recent files are kept because
     * the clipboard may still refer to one of them, but only the latest file is normally useful.
     */
    private fun pruneOldFiles(directory: File, keep: File) {
        directory.listFiles()
            ?.filter { it.isFile && it != keep }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_FILES - 1)
            ?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "ClipboardReceiveFileJob"
        const val CLIPBOARD_DIRECTORY = "clipboard"
        const val MAX_CACHED_FILES = 5
        const val BUFFER_SIZE = 64 * 1024
    }
}
