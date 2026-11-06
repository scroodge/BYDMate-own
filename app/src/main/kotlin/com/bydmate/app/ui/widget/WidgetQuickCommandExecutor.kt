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
        CommandAllowlist.movementBlockReason(type, data)?.let { return Result.Rejected(it) }
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

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetQuickCommandEntryPoint {
    fun widgetQuickCommandExecutor(): WidgetQuickCommandExecutor
}
