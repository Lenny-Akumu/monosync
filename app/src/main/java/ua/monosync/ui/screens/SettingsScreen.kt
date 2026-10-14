package ua.monosync.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ua.monosync.BuildConfig
import ua.monosync.data.AppSettings
import ua.monosync.ui.components.Group
import ua.monosync.ui.components.GroupDivider
import ua.monosync.ui.components.SectionLabel
import ua.monosync.ui.components.SettingRow

@Composable
fun SettingsScreen(
    settings: AppSettings,
    listenerEnabled: Boolean,
    monoConnected: Boolean,
    accountsCount: Int,
    contentPadding: PaddingValues,
    onGrantListener: () -> Unit,
    onMono: () -> Unit,
    onNotion: () -> Unit,
    onAccounts: () -> Unit,
    onCategories: () -> Unit,
    onLog: () -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onNotifyOnRecord: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val chevron: @Composable () -> Unit = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Налаштування", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))

        SectionLabel("Джерела")
        Group {
            SettingRow(
                Icons.Rounded.NotificationsActive,
                "Доступ до сповіщень",
                if (listenerEnabled) "Увімкнено, MonoSync бачить пуші Mono" else "Вимкнено. Натисни, щоб надати",
                trailing = {
                    Icon(
                        if (listenerEnabled) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null,
                        tint = if (listenerEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                },
                onClick = onGrantListener,
            )
            GroupDivider()
            SettingRow(
                Icons.Rounded.AccountBalance,
                "Monobank API",
                when {
                    !monoConnected -> "Не підключено. Баланси й MCC-категорії з банку"
                    settings.monoLastError != null -> "Помилка: ${settings.monoLastError}"
                    else -> "Підключено" + (settings.monoClientName?.let { " · $it" } ?: "")
                },
                trailing = chevron,
                onClick = onMono,
            )
            GroupDivider()
            SettingRow(
                Icons.Rounded.TaskAlt,
                "Notion",
                when {
                    settings.notionConfig == null -> "Не підключено"
                    !settings.notionEnabled -> "Призупинено"
                    settings.notionLastError != null -> "Помилка: ${settings.notionLastError}"
                    else -> "Підключено · ${settings.notionConfig.expenses?.name ?: "база витрат"}"
                },
                trailing = chevron,
                onClick = onNotion,
            )
        }

        SectionLabel("Облік")
        Group {
            SettingRow(Icons.Rounded.CreditCard, "Картки й рахунки", "Знайдено: $accountsCount", trailing = chevron, onClick = onAccounts)
            GroupDivider()
            SettingRow(Icons.Rounded.Category, "Категорії та правила", "Звідки беруться категорії і чого навчився застосунок", trailing = chevron, onClick = onCategories)
            GroupDivider()
            SettingRow(Icons.AutoMirrored.Rounded.ReceiptLong, "Журнал сповіщень", "Сирі тексти від Mono, щоб перевірити розпізнавання", trailing = chevron, onClick = onLog)
        }

        SectionLabel("Вигляд і сповіщення")
        Group {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingRow(
                    Icons.Rounded.Palette, "Кольори зі шпалер", "Material You, як у системних застосунках Pixel",
                    trailing = { Switch(checked = settings.dynamicColor, onCheckedChange = onDynamicColor) },
                    onClick = { onDynamicColor(!settings.dynamicColor) },
                )
                GroupDivider()
            }
            SettingRow(
                Icons.Rounded.NotificationsActive, "Підтверджувати записи", "Тихе сповіщення після кожної записаної операції",
                trailing = { Switch(checked = settings.notifyOnRecord, onCheckedChange = onNotifyOnRecord) },
                onClick = { onNotifyOnRecord(!settings.notifyOnRecord) },
            )
            GroupDivider()
            SettingRow(Icons.Rounded.Widgets, "Віджет балансу", "Утримуй палець на головному екрані → Віджети → MonoSync")
        }

        SectionLabel("Надійність")
        Group {
            val ignoring = isIgnoringBattery(context)
            SettingRow(
                Icons.Rounded.BatteryAlert,
                "Оптимізація батареї",
                if (ignoring) "Вимкнена для MonoSync, фонова робота стабільна" else "Увімкнена. Android може приспати слухача сповіщень",
                trailing = {
                    if (ignoring) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                },
                onClick = { requestIgnoreBattery(context) },
            )
        }

        SectionLabel("Про застосунок")
        Group {
            SettingRow(Icons.Rounded.Info, "MonoSync ${BuildConfig.VERSION_NAME}", "Дані зберігаються лише на телефоні та у твоєму Notion")
        }
        Spacer(Modifier.height(12.dp))
    }
}

fun isIgnoringBattery(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@SuppressLint("BatteryLife")
fun requestIgnoreBattery(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(direct) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
