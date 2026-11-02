package com.bydmate.app.data.cloud

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Base64
import com.bydmate.app.data.remote.CameraSnapshotService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Narrow shell-only IPC: no query surface, ACK only after the Room enqueue commits. */
class DaemonQueueBrokerProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(Binder.getCallingUid() == Process.SHELL_UID) { "Only shell daemon may enqueue" }
        val entryPoint = EntryPointAccessors.fromApplication(
            requireNotNull(context).applicationContext,
            BrokerEntryPoint::class.java,
        )
        return when (method) {
            METHOD_ENQUEUE -> {
                val encoded = requireNotNull(extras?.getString(EXTRA_RECORD)) { "Missing record" }
                require(encoded.length <= MAX_ENCODED_CHARS) { "Record too large" }
                val record = DaemonTelemetrySpool.decode(Base64.decode(encoded, Base64.NO_WRAP))
                val inserted = runBlocking(Dispatchers.IO) { entryPoint.daemonSpoolImporter().importRecord(record) }
                Bundle().apply {
                    putBoolean(RESULT_ACCEPTED, true)
                    putBoolean(RESULT_INSERTED, inserted)
                }
            }
            METHOD_CAMERA_SNAPSHOT -> {
                val commandId = requireNotNull(extras?.getString(EXTRA_COMMAND_ID)) { "Missing command id" }
                val result = runBlocking { entryPoint.cameraSnapshotService().captureAndUpload(commandId) }
                Bundle().apply {
                    putString(RESULT_STATUS, result.status)
                    putString(RESULT_ERROR, result.result["error"] as? String)
                    putString(RESULT_STORAGE_PATH, result.result["storage_path"] as? String)
                    putString(RESULT_CAPTURED_AT, result.result["captured_at"] as? String)
                }
            }
            else -> throw IllegalArgumentException("Unsupported method")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BrokerEntryPoint {
        fun daemonSpoolImporter(): DaemonSpoolImporter
        fun cameraSnapshotService(): CameraSnapshotService
    }

    companion object {
        const val AUTHORITY = "dev.scroodge.cloudevmate.daemon-telemetry"
        const val METHOD_ENQUEUE = "enqueue"
        const val METHOD_CAMERA_SNAPSHOT = "camera_snapshot"
        const val EXTRA_RECORD = "record"
        const val EXTRA_COMMAND_ID = "command_id"
        const val RESULT_ACCEPTED = "accepted"
        const val RESULT_INSERTED = "inserted"
        const val RESULT_STATUS = "status"
        const val RESULT_ERROR = "error"
        const val RESULT_STORAGE_PATH = "storage_path"
        const val RESULT_CAPTURED_AT = "captured_at"
        private const val MAX_ENCODED_CHARS = 90 * 1024
    }
}
