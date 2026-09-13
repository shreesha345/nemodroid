package com.danielealbano.androidremotecontrolmcp.agent

import kotlin.coroutines.cancellation.CancellationException

/** [runCatching] that rethrows [CancellationException] so structured cancellation is never swallowed. */
internal inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
