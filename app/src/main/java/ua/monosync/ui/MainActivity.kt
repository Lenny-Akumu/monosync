package ua.monosync.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.monosync.ui.theme.MonoSyncTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val deepLink = mutableStateOf<DeepLink?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            MonoSyncTheme(dynamic = settings.dynamicColor) {
                AppRoot(vm, deepLink.value, onDeepLinkHandled = { deepLink.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("confirm_account")?.let { deepLink.value = DeepLink.Account(it) }
        intent.getStringExtra("transaction")?.toLongOrNull()?.let { deepLink.value = DeepLink.Transaction(it) }
        intent.getStringExtra("subscriptions")?.let { deepLink.value = DeepLink.Subscriptions }
    }
}

sealed interface DeepLink {
    data class Account(val id: String) : DeepLink
    data class Transaction(val id: Long) : DeepLink
    data object Subscriptions : DeepLink
}
