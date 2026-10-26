package com.glasspay.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
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
    var pendingContactResolution by remember { mutableStateOf<PayerResolution?>(null) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var showMyQr by remember { mutableStateOf(false) }
    var showAddContact by remember { mutableStateOf(false) }
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
        pendingContactResolution = null
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
        showMyQr = false
        showAddContact = false
        tab = next
    }

    var online by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

            override fun onLost(network: Network) {
                online = false
            }
        }
        var registered = false
        try {
            online = manager?.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            manager?.registerDefaultNetworkCallback(callback)
            registered = manager != null
        } catch (_: Exception) {
            online = true
        }
        onDispose {
            if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        }
    }

    // Bloqueo de la app con el bloqueo del teléfono. El contenido NO se desmonta: solo se tapa.
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var locked by remember { mutableStateOf(true) }
    var amountsVisible by remember { mutableStateOf(false) }
    var authAvailability by remember { mutableStateOf(DeviceAuthenticator.availability(context)) }
    var prompting by remember { mutableStateOf(false) }
    val backgroundedAt = remember { longArrayOf(-1L) }
    val promptSignal = remember { arrayOfNulls<android.os.CancellationSignal>(1) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    backgroundedAt[0] = System.currentTimeMillis()
                    amountsVisible = false
                }
                Lifecycle.Event.ON_START -> {
                    authAvailability = DeviceAuthenticator.availability(context)
                    val since = backgroundedAt[0].takeIf { it >= 0 }
                    backgroundedAt[0] = -1L
                    if (shouldLockOnResume(since, System.currentTimeMillis())) locked = true
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            promptSignal[0]?.cancel()
        }
    }

    val current = session
    val lockActive = current != null && locked && authAvailability == DeviceAuthAvailability.AVAILABLE

    fun unlock() {
        val host = activity ?: return
        if (prompting) return
        prompting = true
        promptSignal[0] = DeviceAuthenticator.authenticate(
            host,
            "Desbloquea GlassPay",
            "Usa tu huella o el bloqueo de tu teléfono",
            onSuccess = {
                prompting = false
                locked = false
            },
            onFailure = { prompting = false },
        )
    }

    // El prompt se lanza solo cuando el bloqueo pasa a activo (no en cada ON_START), así no hay bucle.
    LaunchedEffect(lockActive) {
        if (lockActive) unlock()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlassPayColors.Background)
            .then(
                if (lockActive) {
                    Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false }
                } else {
                    Modifier
                },
            ),
    ) {
        if (!online) {
            Text(
                "Sin conexión. Revisa tu red.",
                color = Color(0xFF0B1020),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassPayColors.Warning)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                        locked = false
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
                            AppTab.HOME -> if (showMyQr) {
                                MyQrScreen(
                                    session = current,
                                    api = api,
                                    onSessionInvalid = ::invalidateSession,
                                    onBack = { showMyQr = false },
                                    locked = lockActive,
                                )
                            } else HomeScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onCharge = { selectTab(AppTab.CHARGE) },
                                onOpenMyQr = { showMyQr = true },
                                locked = lockActive,
                                amountsVisible = amountsVisible,
                                onToggleAmounts = { amountsVisible = !amountsVisible },
                                showLockHint = authAvailability != DeviceAuthAvailability.AVAILABLE,
                            )
                            AppTab.MOVEMENTS -> MovementsScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                amountsVisible = amountsVisible,
                            )
                            AppTab.CHARGE -> ChargeScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onFlowStateChange = { resolve, charge ->
                                    flowResolve = resolve
                                    flowCharge = charge
                                },
                                onOpenMovements = { selectTab(AppTab.MOVEMENTS) },
                                locked = lockActive,
                                contactResolution = pendingContactResolution,
                                onContactResolutionConsumed = { pendingContactResolution = null },
                            )
                            AppTab.CONTACTS -> if (showAddContact) {
                                AddContactScreen(
                                    session = current,
                                    api = api,
                                    onSessionInvalid = ::invalidateSession,
                                    onDone = { showAddContact = false },
                                )
                            } else ContactsScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onAddWithQr = { showAddContact = true },
                                onChargeContact = { resolution ->
                                    pendingContactResolution = resolution
                                    selectTab(AppTab.CHARGE)
                                },
                            )
                        }
                    }
                    }
                }
            }
        }
        }
    }
    if (lockActive) {
        BackHandler { activity?.moveTaskToBack(true) }
        LockScreen(onUnlock = { unlock() })
    }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlassPayColors.Background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("GlassPay", style = MaterialTheme.typography.displaySmall, color = Color.White)
        Text("GlassPay está bloqueada", style = MaterialTheme.typography.titleMedium, color = GlassPayColors.MutedText)
        Button(onClick = onUnlock) { Text("Desbloquear") }
    }
}

internal tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private class TabItem(val tab: AppTab, val label: String, val icon: Int)

private val tabItems = listOf(
    TabItem(AppTab.HOME, "Inicio", R.drawable.ic_home),
    TabItem(AppTab.MOVEMENTS, "Movimientos", R.drawable.ic_movements),
    TabItem(AppTab.CHARGE, "Cobrar", R.drawable.ic_charge),
    TabItem(AppTab.CONTACTS, "Contactos", R.drawable.ic_contacts),
)
