package com.bydmate.app.service

/**
 * Runs [block], catching any [Exception] and reporting it via [onError] instead of
 * letting it propagate. Used to keep a single poll-loop step (a Room insert, an
 * Android framework call) from aborting the whole tick before it reaches the cloud
 * push / beacon write — see ADR-0002.
 */
suspend fun <T> runIsolated(
    label: String,
    onError: (label: String, error: Exception) -> Unit,
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (e: Exception) {
        onError(label, e)
        null
    }
