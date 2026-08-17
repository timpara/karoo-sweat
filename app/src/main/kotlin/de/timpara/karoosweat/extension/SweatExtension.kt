package de.timpara.karoosweat.extension

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import de.timpara.karoosweat.BuildConfig
import de.timpara.karoosweat.R
import de.timpara.karoosweat.datatype.BodyLossDataType
import de.timpara.karoosweat.datatype.DrinkTargetDataType
import de.timpara.karoosweat.datatype.HydrationDataType
import de.timpara.karoosweat.datatype.SodiumLossDataType
import de.timpara.karoosweat.datatype.SodiumTargetDataType
import de.timpara.karoosweat.datatype.SweatLossDataType
import de.timpara.karoosweat.datatype.SweatRateDataType
import de.timpara.karoosweat.engine.EnvironmentSource
import de.timpara.karoosweat.engine.SweatEngine
import de.timpara.karoosweat.util.SweatStore
import de.timpara.karoosweat.util.consumerFlow
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The Karoo extension service.
 *
 * Runs in the foreground deliberately. Sweat accumulation is a stateful integral over
 * the whole ride, so letting Android reclaim the process mid-ride would lose the
 * rider's running total; the persistence layer limits the damage but cannot prevent
 * it entirely.
 */
class SweatExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var store: SweatStore
    private lateinit var engine: SweatEngine
    private lateinit var environmentSource: EnvironmentSource

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            SweatLossDataType(EXTENSION_ID, engine),
            SweatRateDataType(EXTENSION_ID, engine),
            DrinkTargetDataType(EXTENSION_ID, engine),
            BodyLossDataType(EXTENSION_ID, engine),
            SodiumLossDataType(EXTENSION_ID, engine),
            SodiumTargetDataType(EXTENSION_ID, engine),
            HydrationDataType(EXTENSION_ID, engine),
        )
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        karooSystem = KarooSystemService(applicationContext)
        store = SweatStore(applicationContext)
        environmentSource = EnvironmentSource(karooSystem, store, scope)
        engine = SweatEngine(karooSystem, store, environmentSource, scope)

        karooSystem.connect { connected ->
            if (connected) {
                environmentSource.start()
                engine.start()
            }
        }
    }

    override fun onDestroy() {
        // Last chance to save; without this a mid-ride kill loses up to 30 seconds.
        runBlocking { engine.persist(force = true) }
        karooSystem.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Write sweat metrics into the recorded FIT file as developer fields.
     *
     * This is what makes the model verifiable after the fact: the recorded estimate
     * can be compared against an actual nude weigh-in to calibrate
     * [de.timpara.karoosweat.model.RiderProfile.sweatMultiplier].
     */
    override fun startFit(emitter: Emitter<FitEffect>) {
        val job = scope.launch {
            if (!store.settings().fitExportEnabled) return@launch

            engine.snapshot
                .filterNotNull()
                .combine(karooSystem.consumerFlow<RideState>()) { snapshot, rideState ->
                    snapshot to rideState
                }
                .collect { (snapshot, rideState) ->
                    when (rideState) {
                        is RideState.Recording -> emitter.onNext(
                            WriteToRecordMesg(
                                listOf(
                                    FieldValue(SWEAT_LOSS_FIELD, snapshot.state.cumulativeSweatMl),
                                    FieldValue(SWEAT_RATE_FIELD, snapshot.state.currentRateMlPerHour),
                                    FieldValue(DRINK_TARGET_FIELD, snapshot.recommendedIntakeMl),
                                    FieldValue(SODIUM_LOSS_FIELD, snapshot.state.cumulativeSodiumMg),
                                    FieldValue(SODIUM_TARGET_FIELD, snapshot.sodium.targetMg),
                                ),
                            ),
                        )

                        is RideState.Paused -> emitter.onNext(
                            WriteToSessionMesg(
                                listOf(
                                    FieldValue(SWEAT_LOSS_FIELD, snapshot.state.cumulativeSweatMl),
                                    FieldValue(SODIUM_LOSS_FIELD, snapshot.state.cumulativeSodiumMg),
                                ),
                            ),
                        )

                        is RideState.Idle -> Unit
                    }
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Sweat tracking",
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sweat tracking active")
            .setSmallIcon(R.drawable.ic_sweat)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val EXTENSION_ID = "sweat"

        private const val CHANNEL_ID = "karoo-sweat"
        private const val NOTIFICATION_ID = 4711

        /** FIT base type 136 is Float32. Field numbers are sequential from zero. */
        private const val FIT_FLOAT32: Short = 136

        private val SWEAT_LOSS_FIELD = DeveloperField(
            fieldDefinitionNumber = 0,
            fitBaseTypeId = FIT_FLOAT32,
            fieldName = "sweat_loss",
            units = "ml",
        )
        private val SWEAT_RATE_FIELD = DeveloperField(
            fieldDefinitionNumber = 1,
            fitBaseTypeId = FIT_FLOAT32,
            fieldName = "sweat_rate",
            units = "ml/h",
        )
        private val DRINK_TARGET_FIELD = DeveloperField(
            fieldDefinitionNumber = 2,
            fitBaseTypeId = FIT_FLOAT32,
            fieldName = "drink_target",
            units = "ml",
        )

        // Appended rather than inserted: field definition numbers are the identity
        // of a developer field, so renumbering the existing three would make old
        // and new FIT files disagree about what each column means.
        private val SODIUM_LOSS_FIELD = DeveloperField(
            fieldDefinitionNumber = 3,
            fitBaseTypeId = FIT_FLOAT32,
            fieldName = "sodium_loss",
            units = "mg",
        )
        private val SODIUM_TARGET_FIELD = DeveloperField(
            fieldDefinitionNumber = 4,
            fitBaseTypeId = FIT_FLOAT32,
            fieldName = "sodium_target",
            units = "mg",
        )
    }
}
