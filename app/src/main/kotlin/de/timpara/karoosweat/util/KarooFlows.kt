package de.timpara.karoosweat.util

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Flow adapters over the callback-based Karoo system API.
 *
 * These helpers live in the karoo-ext *sample app* rather than in the library itself,
 * so every extension has to carry its own copy.
 */

/** Stream a data type as a [Flow], unsubscribing automatically when collection stops. */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val consumerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySend(event.state)
    }
    awaitClose { removeConsumer(consumerId) }
}

/** Observe a parameterless Karoo event as a [Flow]. */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val consumerId = addConsumer<T> { trySend(it) }
    awaitClose { removeConsumer(consumerId) }
}

/** Observe a parameterised Karoo event as a [Flow]. */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(
    params: KarooEventParams,
): Flow<T> = callbackFlow {
    val consumerId = addConsumer<T>(params) { trySend(it) }
    awaitClose { removeConsumer(consumerId) }
}

/** Convenience: the numeric value of a stream, or null when it is not available. */
fun StreamState.value(): Double? = (this as? StreamState.Streaming)?.dataPoint?.singleValue

/** Convenience: a named field from a stream, or null. */
fun StreamState.field(fieldId: String): Double? =
    (this as? StreamState.Streaming)?.dataPoint?.values?.get(fieldId)
