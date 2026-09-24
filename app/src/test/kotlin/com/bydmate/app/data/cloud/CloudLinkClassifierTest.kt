package com.bydmate.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudLinkClassifierTest {

    private val now = 10_000_000L
    private val healthy = CloudLinkEvents(
        enabled = true, upOk = true, lastUpOkMs = now - 30_000L, lastEventMs = now - 30_000L,
        queued = 0, downOk = true,
    )

    private fun level(e: CloudLinkEvents) = CloudLinkClassifier.classify(e, now).level

    @Test fun `disabled wins over everything`() {
        assertEquals(CloudLinkLevel.DISABLED, level(healthy.copy(enabled = false, configError = "x")))
    }

    @Test fun `nothing heard yet is unknown`() {
        assertEquals(CloudLinkLevel.UNKNOWN, level(CloudLinkEvents(enabled = true)))
    }

    @Test fun `uploads and poll both fine is ok`() {
        assertEquals(CloudLinkLevel.OK, level(healthy))
    }

    @Test fun `upload fine but poll failing is no downlink`() {
        assertEquals(CloudLinkLevel.NO_DOWNLINK, level(healthy.copy(downOk = false)))
    }

    @Test fun `short upload outage is queued with the count`() {
        val view = CloudLinkClassifier.classify(
            healthy.copy(upOk = false, lastUpOkMs = now - 2 * 60_000L, queued = 124), now,
        )
        assertEquals(CloudLinkLevel.QUEUED, view.level)
        assertEquals(124, view.queued)
    }

    @Test fun `long upload outage is an error`() {
        assertEquals(
            CloudLinkLevel.ERROR,
            level(healthy.copy(upOk = false, lastUpOkMs = now - CloudLinkClassifier.ERROR_AFTER_MS - 1)),
        )
    }

    @Test fun `never delivered and failing is an error`() {
        assertEquals(CloudLinkLevel.ERROR, level(healthy.copy(upOk = false, lastUpOkMs = 0L)))
    }

    @Test fun `waiting for wifi stays queued however long`() {
        assertEquals(
            CloudLinkLevel.QUEUED,
            level(healthy.copy(upOk = false, lastUpOkMs = 0L, waitingForWifi = true, queued = 5000)),
        )
    }

    @Test fun `broken settings are an error`() {
        assertEquals(CloudLinkLevel.ERROR, level(healthy.copy(configError = "Укажите имя авто")))
    }

    @Test fun `long silence does not claim a live link`() {
        assertEquals(
            CloudLinkLevel.UNKNOWN,
            level(healthy.copy(lastEventMs = now - CloudLinkClassifier.SILENT_AFTER_MS - 1)),
        )
    }
}
