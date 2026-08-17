package de.timpara.karoosweat.datatype

import de.timpara.karoosweat.engine.SweatEngine
import de.timpara.karoosweat.engine.SweatSnapshot
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Base for the plain numeric fields. Each one is just a projection of the engine
 * snapshot onto a single number, so they share everything except that projection.
 */
abstract class SnapshotDataType(
    extension: String,
    typeId: String,
    private val engine: SweatEngine,
    private val select: (SweatSnapshot) -> Double,
) : DataTypeImpl(extension, typeId) {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            engine.snapshot
                .filterNotNull()
                .map(select)
                .collect { value ->
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(
                                dataTypeId = dataTypeId,
                                values = mapOf(DataType.Field.SINGLE to value),
                            ),
                        ),
                    )
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}

/** Cumulative estimated sweat loss, in millilitres. */
class SweatLossDataType(extension: String, engine: SweatEngine) :
    SnapshotDataType(extension, TYPE_ID, engine, { it.state.cumulativeSweatMl }) {
    companion object { const val TYPE_ID = "sweat-loss" }
}

/** Current estimated sweat rate, in millilitres per hour. */
class SweatRateDataType(extension: String, engine: SweatEngine) :
    SnapshotDataType(extension, TYPE_ID, engine, { it.state.currentRateMlPerHour }) {
    companion object { const val TYPE_ID = "sweat-rate" }
}

/** How much the rider should have drunk by now, in millilitres. */
class DrinkTargetDataType(extension: String, engine: SweatEngine) :
    SnapshotDataType(extension, TYPE_ID, engine, { it.recommendedIntakeMl }) {
    companion object { const val TYPE_ID = "drink-target" }
}

/** Cumulative estimated sodium loss, in milligrams. */
class SodiumLossDataType(extension: String, engine: SweatEngine) :
    SnapshotDataType(extension, TYPE_ID, engine, { it.state.cumulativeSodiumMg }) {
    companion object { const val TYPE_ID = "sodium-loss" }
}

/** How much sodium the rider should have taken by now, in milligrams. */
class SodiumTargetDataType(extension: String, engine: SweatEngine) :
    SnapshotDataType(extension, TYPE_ID, engine, { it.sodium.targetMg }) {
    companion object { const val TYPE_ID = "sodium-target" }
}

/** Projected body mass loss as a percentage, the physiologically meaningful figure. */
class BodyLossDataType(extension: String, engine: SweatEngine) :
    SnapshotDataType(extension, TYPE_ID, engine, { it.bodyMassLossFraction * 100.0 }) {
    companion object { const val TYPE_ID = "body-loss" }
}
