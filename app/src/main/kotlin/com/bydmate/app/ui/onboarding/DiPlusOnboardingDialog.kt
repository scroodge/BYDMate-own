package com.bydmate.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydmate.app.onboarding.DiPlusOnboardingPolicy
import com.bydmate.app.onboarding.DiPlusOnboardingState
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

@Composable
fun DiPlusOnboardingDialog(
    state: DiPlusOnboardingState,
    diPlusWasOpened: Boolean,
    onInstall: () -> Unit,
    onOpenDiPlus: () -> Unit,
    onConfirmFirstLaunch: (Long) -> Unit,
    onAcceptOutdated: (Long) -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Для VoltFlow Mate нужен di+", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                when (state) {
                    DiPlusOnboardingState.Missing -> {
                        Text(
                            "di+ не установлен. Без него VoltFlow Mate не получает данные автомобиля, поэтому настройку продолжить нельзя. Установите di+ из доверенного источника и вернитесь сюда.",
                            color = TextSecondary,
                        )
                        Button(
                            onClick = onInstall,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        ) { Text("Открыть страницу установки", color = Color.Black) }
                    }
                    is DiPlusOnboardingState.NeedsFirstLaunch -> {
                        Text(
                            "di+ найден (versionCode ${state.versionCode}). Перед продолжением откройте di+ хотя бы один раз, чтобы он завершил первоначальную настройку и запустил локальный сервис данных.",
                            color = TextSecondary,
                        )
                        Button(
                            onClick = onOpenDiPlus,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        ) { Text("Открыть di+", color = Color.Black) }
                        if (diPlusWasOpened) {
                            OutlinedButton(
                                onClick = { onConfirmFirstLaunch(state.versionCode) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("di+ открыт — продолжить") }
                        }
                    }
                    is DiPlusOnboardingState.Outdated -> {
                        Text(
                            "Установлен di+ versionCode ${state.versionCode}. Для vehicleSegments и chargingSessions нужен versionCode ${DiPlusOnboardingPolicy.MIN_VERSION_CODE_FOR_V2_ENDPOINTS} или выше. Эти данные о сегментах поездок и сессиях зарядки недоступны; базовая телеметрия продолжит работать.",
                            color = TextSecondary,
                        )
                        Button(
                            onClick = { onAcceptOutdated(state.versionCode) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        ) { Text("Продолжить с ограничениями", color = Color.Black) }
                    }
                    is DiPlusOnboardingState.Ready -> Unit
                }
            }
        }
    }
}
