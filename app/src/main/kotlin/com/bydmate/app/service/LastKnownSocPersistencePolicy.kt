package com.bydmate.app.service

/**
 * Offline-charge recovery needs a recent SOC baseline, not a database write for every DiPars
 * poll. Persist immediately on a value change and otherwise refresh the capture time once a
 * minute so a forced stop still has a useful session boundary.
 */
internal object LastKnownSocPersistencePolicy {
    const val MAX_CAPTURE_AGE_MS = 60_000L

    fun shouldPersist(
        currentSoc: Int,
        previousSoc: Int?,
        previousCapturedAtMs: Long,
        nowMs: Long,
    ): Boolean = previousSoc == null ||
        currentSoc != previousSoc ||
        nowMs - previousCapturedAtMs >= MAX_CAPTURE_AGE_MS
}
