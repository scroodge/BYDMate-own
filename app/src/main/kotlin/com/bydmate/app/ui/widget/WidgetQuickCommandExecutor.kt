package com.bydmate.app.ui.widget

import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.autoservice.AutoserviceActuation
import com.bydmate.app.data.remote.CommandAllowlist
import com.bydmate.app.service.TrackingService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Local, explicit actions from the floating widget; they use direct autoservice writes only. */
@Singleton
class WidgetQuickCommandExecutor @Inject constructor(
    private val adbClient: AdbOnDeviceClient,
) {
    sealed interface Result {
        data class Sent(val message: String) : Result
        data class Rejected(val reason: String) : Result
        data object Failed : Result
    }

    /**
     * Current seat-heat state read straight from autoservice (read fids verified on car `way`,
     * docs/AUTOSERVICE_ACTUATION.md). DRL has no reliable read fid, so it is not in the map.
     */
    suspend fun readSeatHeatStates(): Map<WidgetQuickControl, Boolean?> {
        if (!adbClient.isConnected() && adbClient.connect().isFailure) return emptyMap()
        val reads = listOfNotNull(
            AutoserviceActuation.DRIVER_SEAT_HEAT_OFF.readBack,
            AutoserviceActuation.PASSENGER_SEAT_HEAT_OFF.readBack,
        )
        val values = adbClient.execAutoserviceReads(reads) ?: return emptyMap()
        return mapOf(
            WidgetQuickControl.DRIVER_SEAT_HEAT to seatHeatOn(values.getOrNull(0)),
            WidgetQuickControl.PASSENGER_SEAT_HEAT to seatHeatOn(values.getOrNull(1)),
        )
    }

    suspend fun set(control: WidgetQuickControl, enabled: Boolean): Result {
        val (type, action, label) = when (control) {
            WidgetQuickControl.DRIVER_SEAT_HEAT -> Triple(
                "seat_heat_driver",
                if (enabled) AutoserviceActuation.DRIVER_SEAT_HEAT_LEVEL_2
                else AutoserviceActuation.DRIVER_SEAT_HEAT_OFF,
                "Подогрев водителя",
            )
            WidgetQuickControl.DRL -> Triple(
                "drl",
                if (enabled) AutoserviceActuation.DRL_ON else AutoserviceActuation.DRL_OFF,
                "ДХО",
            )
            WidgetQuickControl.PASSENGER_SEAT_HEAT -> Triple(
                "seat_heat_pass",
                if (enabled) AutoserviceActuation.PASSENGER_SEAT_HEAT_LEVEL_2
                else AutoserviceActuation.PASSENGER_SEAT_HEAT_OFF,
                "Подогрев пассажира",
            )
        }
        val data = TrackingService.lastData.value
        // The widget is pressed by someone sitting in the car, so seat heat is a comfort toggle
        // like climate and must work while driving. Remote (web) seat heat stays parked-only via
        // CommandAllowlist; DRL keeps the movement gate here too.
        if (control == WidgetQuickControl.DRL) {
            CommandAllowlist.movementBlockReason(type, data)?.let { return Result.Rejected(it) }
        }
        CommandAllowlist.auxVoltageBlockReason(data)?.let { return Result.Rejected(it) }

        if (!adbClient.isConnected() && adbClient.connect().isFailure) return Result.Failed
        val outcome = adbClient.executeAutoserviceActuation(action) ?: return Result.Failed
        if (!outcome.accepted || outcome.verified == false) return Result.Failed
        return if (outcome.accepted) {
            Result.Sent("$label: ${if (enabled) "включено" else "выключено"}")
        } else {
            Result.Failed
        }
    }
}

/** Seat-heat read value: 1 off, 2 level 1, 3 level 2; anything else is unknown. */
internal fun seatHeatOn(raw: Int?): Boolean? = when (raw) {
    1 -> false
    2, 3 -> true
    else -> null
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetQuickCommandEntryPoint {
    fun widgetQuickCommandExecutor(): WidgetQuickCommandExecutor
}
