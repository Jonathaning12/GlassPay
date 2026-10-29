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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
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
    // Con sesión, revisión periódica de programaciones que esperan confirmación (WorkManager, C3).
    LaunchedEffect(session != null) {
        if (session != null) scheduleConfirmationChecks(context)
    }
    var loaded by remember { mutableStateOf(false) }
    var welcomeMessage by remember { mutableStateOf<String?>(null) }
    var pendingContactResolution by remember { mutableStateOf<PayerResolution?>(null) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(AppTab.HOME) }
    val haptics = LocalHapticFeedback.current
    var showMyQr by remember { mutableStateOf(false) }
    var showAddContact by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var sendContact by remember { mutableStateOf<Contact?>(null) }
    var showPayoutEmail by remember { mutableStateOf(false) }
    var showScheduled by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    // Cobrar ya no es pestaña: es una pantalla completa que se abre desde Inicio, el perfil o un contacto.
    var showCharge by remember { mutableStateOf(false) }
    // Conversación del asistente: solo en memoria; se descarta al salir del asistente.
    val assistantChat = remember { mutableStateOf(ChatState()) }
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
        showScheduled = false
        showProfile = false
        showCharge = false
        assistantChat.value = ChatState()
        cancelConfirmationChecks(context)
        tab = AppTab.HOME
        flowResolve = ResolveState.Scanning
        flowCharge = ChargeState.Ready()
        scope.launch { withContext(Dispatchers.IO) { runCatching { sessionStore.clear() } } }
    }

    fun selectTab(next: AppTab) {
        flowResolve = ResolveState.Scanning
        flowCharge = ChargeState.Ready()
        saveNotice = null
        showMyQr = false
        showAddContact = false
        showSend = false
        sendContact = null
        showPayoutEmail = false
        showScheduled = false
        showStats = false
        showProfile = false
        showCharge = false
        pendingContactResolution = null
        // La conversación solo vive mientras se está en la pestaña Asistente (o en Programados desde ella).
        if (tab == AppTab.ASSISTANT && next != AppTab.ASSISTANT) assistantChat.value = ChatState()
        tab = next
    }

    fun openCharge() {
        flowResolve = ResolveState.Scanning
        flowCharge = ChargeState.Ready()
        saveNotice = null
        showCharge = true
    }

    fun closeCharge() {
        showCharge = false
        pendingContactResolution = null
        flowResolve = ResolveState.Scanning
        flowCharge = ChargeState.Ready()
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassPayColors.Warning)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_wifi_off),
                    contentDescription = null,
                    tint = GlassPayColors.Background,
                    modifier = Modifier.size(20.dp),
                )
                Text("Sin conexión. Revisa tu red.", color = GlassPayColors.Background, style = MaterialTheme.typography.labelLarge)
            }
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
                val canLeaveCharge = allowsTabSwitch(flowResolve, flowCharge)
                // Durante Cobrar no hay barra: se sale con "Volver" solo si no se pierde un cobro en curso.
                val showBar = !showCharge
                BackHandler(enabled = showCharge) { if (canLeaveCharge) closeCharge() }
                Scaffold(
                    containerColor = GlassPayColors.Background,
                    bottomBar = {
                        if (showBar) {
                            Column {
                                HorizontalDivider(color = GlassPayColors.Line)
                                NavigationBar(containerColor = GlassPayColors.Card, tonalElevation = 0.dp) {
                                    tabItems.forEach { item ->
                                        NavigationBarItem(
                                            selected = tab == item.tab,
                                            onClick = {
                                                if (tab != item.tab) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                selectTab(item.tab)
                                            },
                                            icon = { TabIcon(item.icon, selected = tab == item.tab) },
                                            label = { Text(item.label, style = MaterialTheme.typography.bodySmall) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = GlassPayColors.Accent,
                                                selectedTextColor = GlassPayColors.Text,
                                                indicatorColor = GlassPayColors.AccentSoft,
                                                unselectedIconColor = GlassPayColors.MutedText,
                                                unselectedTextColor = GlassPayColors.MutedText,
                                            ),
                                        )
                                    }
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
                        // Subpantallas primero (en orden de prioridad); al volver de una, se ve la que la abrió.
                        when {
                            showCharge -> Column(modifier = Modifier.fillMaxSize()) {
                                ScreenHeader("Cobrar", onBack = if (canLeaveCharge) ::closeCharge else null)
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    ChargeScreen(
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
                                }
                            }
                            showMyQr -> MyQrScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onBack = { showMyQr = false },
                                locked = lockActive,
                            )
                            showStats -> StatsScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onBack = { showStats = false },
                            )
                            showScheduled -> ScheduledScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onBack = { showScheduled = false },
                            )
                            showPayoutEmail -> PayoutEmailScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onBack = { showPayoutEmail = false },
                            )
                            showSend -> SendMoneyScreen(
                                session = current,
                                api = api,
                                initialContact = sendContact,
                                locked = lockActive,
                                onSessionInvalid = ::invalidateSession,
                                onBack = {
                                    showSend = false
                                    sendContact = null
                                },
                                onOpenMovements = { selectTab(AppTab.MOVEMENTS) },
                            )
                            showAddContact -> AddContactScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onDone = { showAddContact = false },
                            )
                            showProfile -> ProfileScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onBack = { showProfile = false },
                                onCharge = ::openCharge,
                                onSend = {
                                    sendContact = null
                                    showSend = true
                                },
                                onOpenMyQr = { showMyQr = true },
                                onPayoutEmail = { showPayoutEmail = true },
                                onOpenScheduled = { showScheduled = true },
                                amountsVisible = amountsVisible,
                                onToggleAmounts = { amountsVisible = !amountsVisible },
                                lockAvailability = authAvailability,
                            )
                            tab == AppTab.HOME -> HomeScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onCharge = ::openCharge,
                                onOpenProfile = { showProfile = true },
                                onOpenScheduled = { showScheduled = true },
                                onOpenStats = { showStats = true },
                                locked = lockActive,
                                amountsVisible = amountsVisible,
                                onToggleAmounts = { amountsVisible = !amountsVisible },
                                showLockHint = authAvailability != DeviceAuthAvailability.AVAILABLE,
                            )
                            tab == AppTab.MOVEMENTS -> MovementsScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                amountsVisible = amountsVisible,
                                onToggleAmounts = { amountsVisible = !amountsVisible },
                            )
                            tab == AppTab.ASSISTANT -> AssistantScreen(
                                session = current,
                                api = api,
                                chatState = assistantChat,
                                onSessionInvalid = ::invalidateSession,
                                onOpenScheduled = { showScheduled = true },
                            )
                            else -> ContactsScreen(
                                session = current,
                                api = api,
                                onSessionInvalid = ::invalidateSession,
                                onAddWithQr = { showAddContact = true },
                                onSend = {
                                    sendContact = it
                                    showSend = true
                                },
                                onChargeContact = { resolution ->
                                    openCharge()
                                    pendingContactResolution = resolution
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
        IconBadge(R.drawable.ic_lock, tint = GlassPayColors.Accent, size = 88.dp, background = GlassPayColors.AccentSoft)
        Text("GlassPay", style = MaterialTheme.typography.headlineLarge, color = GlassPayColors.Text)
        Text(
            "Está bloqueada. Usa la huella o el bloqueo de tu teléfono.",
            style = MaterialTheme.typography.bodyLarge,
            color = GlassPayColors.MutedText,
            textAlign = TextAlign.Center,
        )
        GpButton("Desbloquear", onClick = onUnlock, icon = R.drawable.ic_lock, modifier = Modifier.fillMaxWidth())
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

/**
 * Ícono de pestaña con un rebote corto al quedar seleccionado (no al arrancar ni al deseleccionar).
 * Cambiar de pestaña es frecuente: la animación dura ~300 ms y se puede interrumpir con otro toque.
 */
@Composable
private fun TabIcon(icon: Int, selected: Boolean) {
    val animate = animationsEnabled()
    val pop = remember { Animatable(1f) }
    val lift = remember { Animatable(0f) }
    var wasSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !wasSelected && animate) {
            pop.snapTo(0.8f)
            lift.snapTo(1f)
            launch { lift.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        }
        wasSelected = selected
    }
    Icon(
        painterResource(icon),
        contentDescription = null,
        modifier = Modifier.graphicsLayer {
            scaleX = pop.value
            scaleY = pop.value
            translationY = lift.value * 4.dp.toPx()
        },
    )
}

private val tabItems = listOf(
    TabItem(AppTab.HOME, "Inicio", R.drawable.ic_home),
    TabItem(AppTab.MOVEMENTS, "Movimientos", R.drawable.ic_movements),
    TabItem(AppTab.ASSISTANT, "Asistente", R.drawable.ic_assistant),
    TabItem(AppTab.CONTACTS, "Contactos", R.drawable.ic_contacts),
)
