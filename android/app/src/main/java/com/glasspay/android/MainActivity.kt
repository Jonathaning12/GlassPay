package com.glasspay.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GlassPayTheme { GlassPayRoot() } }
    }
}

@Composable
fun GlassPayRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { PaymentApi() }
    val sessionStore = remember {
        CollectorSessionStore(SharedPreferencesStore(context), KeystoreSecretCipher())
    }
    var session by remember { mutableStateOf<CollectorSession?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var welcomeMessage by remember { mutableStateOf<String?>(null) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var flowResolve by remember { mutableStateOf<ResolveState>(ResolveState.Scanning) }
    var flowCharge by remember { mutableStateOf<ChargeState>(ChargeState.Ready()) }

    LaunchedEffect(Unit) {
        session = withContext(Dispatchers.IO) { runCatching { sessionStore.load() }.getOrNull() }
        loaded = true
    }
    LaunchedEffect(Unit) {
        try {
            api.warmUp()
        } catch (exception: ApiException) {
            Log.i("GlassPay", "health failure type=${exception.type} httpCode=${exception.httpCode}")
        }
    }

    fun invalidateSession(message: String) {
        welcomeMessage = message
        session = null
        tab = AppTab.HOME
        flowResolve = ResolveState.Scanning
        flowCharge = ChargeState.Ready()
        scope.launch { withContext(Dispatchers.IO) { runCatching { sessionStore.clear() } } }
    }

    fun selectTab(next: AppTab) {
        if (next != AppTab.CHARGE) {
            flowResolve = ResolveState.Scanning
            flowCharge = ChargeState.Ready()
        }
        saveNotice = null
        tab = next
    }

    val current = session
    Box(modifier = Modifier.fillMaxSize().background(GlassPayColors.Background)) {
        when {
            !loaded -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            current == null -> WelcomeScreen(
                api = api,
                message = welcomeMessage,
                onAccountCreated = { created ->
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            runCatching { sessionStore.save(created) }.isSuccess
                        }
                        welcomeMessage = null
                        saveNotice = if (saved) null else "No pudimos guardar tu sesión en este teléfono."
                        tab = AppTab.HOME
                        session = created
                    }
                },
            )
            else -> {
                val showBar = tab != AppTab.CHARGE || allowsTabSwitch(flowResolve, flowCharge)
                BackHandler(enabled = tab == AppTab.CHARGE && !allowsTabSwitch(flowResolve, flowCharge)) { }
                Scaffold(
                    containerColor = GlassPayColors.Background,
                    bottomBar = {
                        if (showBar) {
                            NavigationBar {
                                tabItems.forEach { item ->
                                    NavigationBarItem(
                                        selected = tab == item.tab,
                                        onClick = { selectTab(item.tab) },
                                        icon = { Icon(painterResource(item.icon), contentDescription = null) },
                                        label = { Text(item.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { inner ->
                    Column(modifier = Modifier.padding(inner).fillMaxSize()) {
                        saveNotice?.let {
                            Text(it, color = GlassPayColors.Warning, modifier = Modifier.padding(16.dp))
                        }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            AppTab.HOME -> HomeScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onCharge = { selectTab(AppTab.CHARGE) },
                            )
                            AppTab.MOVEMENTS -> MovementsScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                            )
                            AppTab.CHARGE -> ChargeScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onFlowStateChange = { resolve, charge ->
                                    flowResolve = resolve
                                    flowCharge = charge
                                },
                            )
                            AppTab.CONTACTS -> ContactsScreen()
                        }
                    }
                    }
                }
            }
        }
    }
}

private class TabItem(val tab: AppTab, val label: String, val icon: Int)

private val tabItems = listOf(
    TabItem(AppTab.HOME, "Inicio", R.drawable.ic_home),
    TabItem(AppTab.MOVEMENTS, "Movimientos", R.drawable.ic_movements),
    TabItem(AppTab.CHARGE, "Cobrar", R.drawable.ic_charge),
    TabItem(AppTab.CONTACTS, "Contactos", R.drawable.ic_contacts),
)
