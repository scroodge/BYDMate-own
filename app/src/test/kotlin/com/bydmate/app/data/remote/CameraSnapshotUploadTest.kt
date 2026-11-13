package com.bydmate.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraSnapshotUploadTest {

    @Test
    fun derivesTheUploadUrlFromTheTelemetryUrl() {
        val id = "0b1e7a52-4c3f-4e8a-9d61-2f5b8c7e1a90"
        assertEquals(
            "https://example.test/api/bydmate/camera-snapshots/$id",
            CameraSnapshotUpload.urlFromTelemetry("https://example.test/api/bydmate/telemetry/", id),
        )
        assertEquals(
            "https://example.test/api/bydmate/camera-snapshots/$id",
            CameraSnapshotUpload.urlFromTelemetry(" https://example.test/api/bydmate/ingest ", id),
        )
        assertNull(CameraSnapshotUpload.urlFromTelemetry("https://example.test/other", id))
        assertNull(CameraSnapshotUpload.urlFromTelemetry("", id))
    }
}
