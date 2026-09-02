package com.bydmate.app.data.cloud

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Base64
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
        require(method == METHOD_ENQUEUE) { "Unsupported method" }
        val encoded = requireNotNull(extras?.getString(EXTRA_RECORD)) { "Missing record" }
        require(encoded.length <= MAX_ENCODED_CHARS) { "Record too large" }
        val record = DaemonTelemetrySpool.decode(Base64.decode(encoded, Base64.NO_WRAP))
        val importer = EntryPointAccessors.fromApplication(
            requireNotNull(context).applicationContext,
            BrokerEntryPoint::class.java,
        ).daemonSpoolImporter()
        val inserted = runBlocking(Dispatchers.IO) { importer.importRecord(record) }
        return Bundle().apply {
            putBoolean(RESULT_ACCEPTED, true)
            putBoolean(RESULT_INSERTED, inserted)
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
    }

    companion object {
        const val AUTHORITY = "dev.scroodge.cloudevmate.daemon-telemetry"
        const val METHOD_ENQUEUE = "enqueue"
        const val EXTRA_RECORD = "record"
        const val RESULT_ACCEPTED = "accepted"
        const val RESULT_INSERTED = "inserted"
        private const val MAX_ENCODED_CHARS = 90 * 1024
    }
}
