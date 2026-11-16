package io.github.magisk317.xposed.backup

import android.content.Context
import java.io.File

/**
 * Seam between the transport-agnostic cloud backup stack and the app's own
 * backup payload format. The consuming app (or its product-data layer) decides
 * what goes into the archive and how a restore is applied; transports such as
 * the WebDAV manager only move the produced file around.
 */
interface BackupPayloadSource {
    /**
     * Produces a fresh backup archive owned by the caller, which deletes the
     * file once it has been uploaded. Must throw when the export fails.
     */
    suspend fun produceBackup(context: Context): File

    /**
     * Applies the contents of [backup] to the local product data. Must throw
     * when the restore fails; the caller deletes the file afterwards.
     */
    suspend fun applyRestore(context: Context, backup: File)

    /**
     * Prefix that identifies this app's archives in remote listings and that
     * [produceBackup] must encode into the produced file name, so the transport
     * can upload the archive under its own name and keep listings scoped to
     * this app even when several apps share one WebDAV folder. Empty means the
     * transport lists every non-directory entry.
     */
    val archivePrefix: String get() = ""
}
