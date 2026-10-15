package ua.monosync.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ua.monosync.data.AppSettings
import ua.monosync.data.DEFAULT_EB_REDIRECT
import ua.monosync.data.remote.EbAspsp
import ua.monosync.ui.Busy
import ua.monosync.ui.components.Group
import ua.monosync.ui.components.GroupDivider
import ua.monosync.ui.components.SectionLabel
import ua.monosync.ui.components.SelectDialog
import ua.monosync.ui.components.SettingRow
import ua.monosync.ui.components.dateTimeOf

/** Regional-indicator emoji flag for an ISO country code ("NO" -> 🇳🇴). */
fun flag(country: String): String =
    country.uppercase().filter { it in 'A'..'Z' }.take(2)
        .map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

data class Country(val code: String, val name: String)

val ebCountries = listOf(
    Country("NO", "Норвегія"), Country("SE", "Швеція"), Country("DK", "Данія"), Country("FI", "Фінляндія"),
    Country("DE", "Німеччина"), Country("PL", "Польща"), Country("CZ", "Чехія"), Country("SK", "Словаччина"),
    Country("LT", "Литва"), Country("LV", "Латвія"), Country("EE", "Естонія"), Country("NL", "Нідерланди"),
    Country("BE", "Бельгія"), Country("AT", "Австрія"), Country("FR", "Франція"), Country("ES", "Іспанія"),
    Country("PT", "Португалія"), Country("IT", "Італія"), Country("IE", "Ірландія"), Country("IS", "Ісландія"),
)

/** Banks offered in the picker; Enable Banking ones carry the exact ASPSP name the API expects. */
data class BankPreset(val key: String, val name: String, val country: String, val aspsp: String?, val note: String)

val bankPresets = listOf(
    BankPreset("mono", "Monobank", "UA", null, "Сповіщення та Monobank API"),
    BankPreset("skue", "Skue Sparebank", "NO", "Skue Sparebank", "Через Enable Banking і BankID"),
)

@Composable
fun BanksScreen(
    settings: AppSettings,
    monoConnected: Boolean,
    ebConnected: Boolean,
    onBack: () -> Unit,
    onMonoEnabled: (Boolean) -> Unit,
    onOpenMono: () -> Unit,
    onOpenEb: (BankPreset?) -> Unit,
) {
    SubScreen("Банки", onBack) {
        Hint("Вибери банки, з якими працюєш. Monobank читається зі сповіщень і через API, європейські банки підключаються через Enable Banking і BankID.")
        SectionLabel("Україна")
        Group {
            SettingRow(
                null, "${flag("UA")}  Monobank",
                when {
                    !settings.monoEnabled -> "Вимкнено"
                    monoConnected -> "Сповіщення й API підключено"
                    else -> "Сповіщення. API можна додати"
                },
                trailing = { Switch(checked = settings.monoEnabled, onCheckedChange = onMonoEnabled) },
                onClick = { if (settings.monoEnabled) onOpenMono() else onMonoEnabled(true) },
            )
        }
        SectionLabel("Європа")
        Group {
            val connectedName = settings.eb.aspspName.takeIf { ebConnected }
            bankPresets.filter { it.aspsp != null }.forEachIndexed { i, b ->
                if (i > 0) GroupDivider()
                SettingRow(
                    null, "${flag(b.country)}  ${b.name}",
                    if (connectedName == b.aspsp) "Підключено" else b.note,
                    trailing = {
                        if (connectedName == b.aspsp) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                    },
                    onClick = { onOpenEb(b) },
                )
            }
            GroupDivider()
            val other = connectedName != null && bankPresets.none { it.aspsp == connectedName }
            SettingRow(
                Icons.Rounded.Public, if (other) "${flag(settings.eb.aspspCountry)}  $connectedName" else "Інший банк Європи",
                if (other) "Підключено" else "Будь-який банк з каталогу Enable Banking",
                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                onClick = { onOpenEb(null) },
            )
        }
    }
}

@Composable
fun EbScreen(
    settings: AppSettings,
    preset: BankPreset?,
    hasKey: Boolean,
    connected: Boolean,
    running: Boolean,
    busy: Busy,
    aspsps: List<EbAspsp>,
    authUrl: String?,
    onBack: () -> Unit,
    onAppId: (String) -> Unit,
    onRedirect: (String) -> Unit,
    onImportKey: (String) -> Unit,
    onLoadAspsps: (String) -> Unit,
    onBank: (String, String) -> Unit,
    onLogin: (String, String) -> Unit,
    onAuthResult: (String) -> Unit,
    onCloseAuth: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val eb = settings.eb
    var appId by rememberSaveable { mutableStateOf(eb.appId) }
    var redirect by rememberSaveable { mutableStateOf(eb.redirectUrl.ifBlank { DEFAULT_EB_REDIRECT }) }
    var manual by rememberSaveable { mutableStateOf("") }
    var pickCountry by remember { mutableStateOf(false) }
    var pickBank by remember { mutableStateOf(false) }
    var country by rememberSaveable { mutableStateOf(preset?.country ?: eb.aspspCountry.ifBlank { "NO" }) }

    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        if (text != null) onImportKey(text)
    }

    val bankName = preset?.aspsp ?: eb.aspspName
    val title = preset?.name ?: "Банк Європи"

    SubScreen(title, onBack, busy) {
        if (connected && eb.aspspName.isNotBlank()) {
            Group {
                SettingRow(
                    null, "${flag(eb.aspspCountry)}  ${eb.aspspName}",
                    when {
                        eb.lastError != null -> "Помилка: ${eb.lastError}"
                        eb.lastSync > 0 -> "Синхронізовано ${dateTimeOf(eb.lastSync)}"
                        else -> "Підключено, перша синхронізація триває"
                    },
                )
                GroupDivider()
                SettingRow(
                    Icons.Rounded.Sync, "Синхронізувати зараз",
                    if (eb.validUntil > 0) "Доступ діє до ${dateTimeOf(eb.validUntil)}" else "Баланси й нові операції",
                    trailing = { if (running) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.5.dp) },
                    onClick = onSync,
                )
                GroupDivider()
                SettingRow(
                    Icons.Rounded.Link, "Увійти ще раз", "Якщо доступ закінчився або хочеш додати рахунки",
                    onClick = { onLogin(eb.aspspName, eb.aspspCountry) },
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Відключити банк") }
            Spacer(Modifier.height(8.dp))
        } else {
            Hint(
                "Банк підключається через Enable Banking: це ліцензований сервіс open banking. " +
                    "Потрібен власний безкоштовний акаунт на enablebanking.com з застосунком і прив'язаними рахунками. Покрокову інструкцію дасть той, хто поділився з тобою monosync."
            )
        }

        SectionLabel("1. Ключ Enable Banking")
        Group {
            SettingRow(
                Icons.AutoMirrored.Rounded.OpenInNew, "Відкрити Control Panel", "enablebanking.com → API applications",
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://enablebanking.com/cp/applications"))) } },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it; onAppId(it) },
            label = { Text("Application ID") },
            placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Group {
            SettingRow(
                Icons.Rounded.Key, if (hasKey) "Ключ .pem завантажено" else "Вибрати файл ключа .pem",
                if (hasKey) "Зберігається зашифрованим лише на цьому телефоні" else "Файл, що завантажився при створенні застосунку",
                trailing = { if (hasKey) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { pickKey.launch(arrayOf("*/*")) },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = redirect,
            onValueChange = { redirect = it; onRedirect(it) },
            label = { Text("Redirect URL (як у Control Panel)") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("2. Банк")
        Group {
            if (preset != null) {
                SettingRow(null, "${flag(preset.country)}  ${preset.name}", "Країна: ${ebCountries.firstOrNull { it.code == preset.country }?.name ?: preset.country}")
            } else {
                SettingRow(
                    null, "${flag(country)}  ${ebCountries.firstOrNull { it.code == country }?.name ?: country}", "Країна",
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    onClick = { pickCountry = true },
                )
                GroupDivider()
                SettingRow(
                    null, eb.aspspName.takeIf { eb.aspspCountry == country && it.isNotBlank() } ?: "Вибрати банк",
                    if (hasKey && appId.isNotBlank()) "Список банків з Enable Banking" else "Спершу встав Application ID і ключ",
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    onClick = {
                        if (hasKey && appId.isNotBlank()) {
                            onLoadAspsps(country)
                            pickBank = true
                        }
                    },
                )
            }
        }

        SectionLabel("3. Вхід через BankID")
        Button(
            onClick = {
                if (preset?.aspsp != null) onLogin(preset.aspsp, preset.country) else onLogin(eb.aspspName, eb.aspspCountry.ifBlank { country })
            },
            enabled = hasKey && appId.isNotBlank() && bankName.isNotBlank() && busy is Busy.Idle,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (connected) "Увійти в банк ще раз" else "Увійти в банк") }
        Text(
            "Відкриється сторінка банку. Після входу через BankID ти повернешся в monosync автоматично.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text("Якщо після входу відкрилась сторінка з помилкою 404, скопіюй її адресу й встав сюди:", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            label = { Text("Адреса сторінки після входу") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { onAuthResult(manual); manual = "" }, enabled = manual.contains("code=")) { Text("OK") }
            },
        )
    }

    if (pickCountry) {
        SelectDialog(
            "Країна", ebCountries, ebCountries.firstOrNull { it.code == country }, { "${flag(it.code)}  ${it.name}" },
            { country = it.code }, { pickCountry = false },
        )
    }
    if (pickBank) {
        SelectDialog(
            "Банк", aspsps, aspsps.firstOrNull { it.name == eb.aspspName }, { it.name },
            { onBank(it.name, it.country) }, { pickBank = false },
        )
    }
    authUrl?.let { url -> BankLoginDialog(url, redirect, onResult = onAuthResult, onClose = onCloseAuth) }
}

/** Bank login inside the app: the redirect back to us is caught here, so the redirect page never has to exist. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BankLoginDialog(url: String, redirect: String, onResult: (String) -> Unit, onClose: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    val target = redirect.trimEnd('/')
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрити") }
                    Text("Вхід у банк", style = TextStyle(fontSize = 18.sp), modifier = Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(Modifier.padding(end = 12.dp).height(20.dp), strokeWidth = 2.dp)
                }
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            var handled = false
                            val deliver: (String) -> Unit = { link -> if (!handled) { handled = true; onResult(link) } }
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    private fun handle(view: WebView, link: String): Boolean {
                                        if (link.trimEnd('/').startsWith(target) && (link.contains("code=") || link.contains("error="))) {
                                            deliver(link)
                                            return true
                                        }
                                        val scheme = Uri.parse(link).scheme?.lowercase()
                                        if (scheme != "http" && scheme != "https") {
                                            runCatching {
                                                val intent = if (scheme == "intent") Intent.parseUri(link, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                                view.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            }
                                            return true
                                        }
                                        return false
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                        handle(view, request.url.toString())

                                    override fun onPageStarted(view: WebView, link: String, favicon: Bitmap?) {
                                        loading = true
                                        if (link.trimEnd('/').startsWith(target) && link.contains("code=")) {
                                            view.stopLoading()
                                            deliver(link)
                                        }
                                    }

                                    override fun onPageFinished(view: WebView, link: String) {
                                        loading = false
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Suppress("unused")
private val arrangement = Arrangement.spacedBy(0.dp)
