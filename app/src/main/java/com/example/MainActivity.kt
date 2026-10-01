package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.data.PreferenceManager
import kotlin.math.roundToInt
import com.example.data.database.ProfileEntity
import com.example.manager.boostsession.BoostSessionState
import com.example.manager.boostsession.RestoreResult
import com.example.data.repository.FsmState
import com.example.data.repository.SystemMetrics
import com.example.manager.ProfileManager
import com.example.service.GameBoostService
import com.example.service.UnifiedAccessibilityService
import com.example.ui.FloatingPanelManager
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.Ember
import com.example.ui.theme.HairLine
import com.example.ui.theme.Ok
import com.example.ui.AppIcons
import com.example.ui.profileIcon
import com.example.ui.stripEmoji
import androidx.compose.ui.res.painterResource
import com.example.ui.theme.WarningOrange
import com.example.ui.theme.ErrorRed
import com.example.ui.viewmodel.GameBoostViewModel
import rikka.shizuku.Shizuku

/** Valores discretos para el slider de DPI: de 280 a 600 en pasos de 40 */
private val DPI_STEPS = listOf(280, 320, 360, 400, 440, 480, 520, 560, 600)

class MainActivity : ComponentActivity() {

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        checkAndRequestPermissions(onlySilentCheck = true)
        // Hallazgo #5 (shizuku-off-t1): notificar al manager que el binder de Shizuku
        // reapareció (server reiniciado). Sin esto, _shizukuConnected queda stale y el
        // gate de simulateGameLaunch bloquea detecciones válidas hasta reiniciar la app.
        com.example.data.repository.GameBoostRepository
            .getInstance(applicationContext).onShizukuBinderReceived()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        checkAndRequestPermissions(onlySilentCheck = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        ProfileManager.init(this)
        
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        
        checkAndRequestPermissions(onlySilentCheck = false)

        // ── Iniciar GameBoostService como foreground service ANTI-LMK ──
        // El servicio foreground con notificación protege el proceso del Low Memory Killer.
        // Se inicia siempre al abrir la app, independientemente del estado guardado.
        // Si el servicio es matado, START_REDELIVER_INTENT + watchdog lo reinician.
        ensureGameBoostServiceRunning()

        // Antivirus: proteksi real-time untuk aplikasi baru
        com.example.security.AntivirusManager.start(this)

        setContent {
            MyApplicationTheme {
                val viewModel: GameBoostViewModel = viewModel(
                    factory = GameBoostViewModel.Factory(LocalContext.current)
                )
                GameBoostApp(viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        // ❌ NO destruir el overlay flotante aquí.
        // El overlay es una ventana independiente (WindowManager) que NO depende
        // del ciclo de vida de la Activity. Si la Activity es destruida por el sistema
        // (presión de memoria durante el juego), el overlay se pierde.
        // El overlay debe ser gestionado únicamente por GameBoostService.
        // FloatingPanelManager.getInstance(this).destroy() — NO USAR
    }
    
    /**
     * Inicia GameBoostService si no está ya corriendo.
     * Es independiente de PreferenceManager.isServiceRunning() para asegurar
     * que el servicio arranque incluso después de un crash o kill del proceso.
     */
    private fun ensureGameBoostServiceRunning() {
        try {
            if (!com.example.service.GameBoostService.isRunning) {
                val intent = Intent(this, com.example.service.GameBoostService::class.java)
                // ⚠️ SIN ACTION_START. El servicio se inicia solo con la notificación
                // (LMK protection) pero NO aplica perfiles ni restaura settings.
                // ACTION_START se envía SOLO desde toggleBoost() cuando el usuario o
                // la detección de juego activa el boost.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Log.d("MainActivity", "🚀 GameBoostService iniciado (LMK protection, idle)")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error al iniciar GameBoostService: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions(onlySilentCheck = true)

        // (d) R1 (C5): re-evaluar overlay al reabrir la app es parte de la proyección:
        // resetear el request de usuario (null) hace que el observador del servicio
        // re-muestre el overlay si el boost sigue activo (el ✕ lo había ocultado).
        // No activa boost ni perf mode: solo refleja el estado ya activo.
        try {
            val repo = com.example.data.repository.GameBoostRepository.getInstance(this)
            repo.setOverlayRequested(null)
        } catch (e: Exception) {
            Log.w("GameBoostApp", "onResume overlay re-eval: ${e.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        // No ocultar el panel aquí si el servicio está corriendo.
        // Solo lo ocultamos si realmente queremos forzar la UI de la app.
    }

    override fun onStop() {
        super.onStop()
        // El servicio GameBoostService ya se encarga de mostrar el panel
        // si el boost está activo a través de su propio monitoreo.
    }

    private fun checkAndRequestPermissions(onlySilentCheck: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) {
            if (!onlySilentCheck) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }

        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (!onlySilentCheck) Shizuku.requestPermission(0)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!onlySilentCheck) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // C. Solicitar exención de optimización de batería (doze whitelist / "sin
        // restricciones" en MIUI) para que el servicio en background no sea matado.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                if (!onlySilentCheck) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.w("MainActivity", "No se pudo abrir ajustes de batería: ${e.message}")
                    }
                }
            }
        }
    }

    /** Detección robusta vía AccessibilityManager (no lee Settings.Secure por
     *  string — evita lecturas stale en proceso en Android 8+). */
    @Suppress("DEPRECATION")
    fun isAccessibilityServiceEnabled(): Boolean {
        if (com.example.service.UnifiedAccessibilityService.isServiceRunning) return true
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = ComponentName(this, com.example.service.UnifiedAccessibilityService::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == expected.packageName &&
                   it.resolveInfo.serviceInfo.name == expected.className }
    }
}

enum class NavigationTab {
    DASBOR, OPTIMASI, LOG, DIAGNOSIS, KEAMANAN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBoostApp(viewModel: GameBoostViewModel) {
    var selectedTab by remember { mutableStateOf(NavigationTab.DASBOR) }
    val shizukuConnected by viewModel.shizukuConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, modifier = Modifier.size(26.dp))
                        Text("GameBoost", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = { FloatingPanelManager.getInstance(context).toggleVisibility() }) {
                        Icon(AppIcons.Grid, contentDescription = "Panel", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { 
                        if (!Shizuku.pingBinder()) {
                            try {
                                val intent = context.packageManager.getLaunchIntentForPackage("rikka.shizuku")
                                if (intent != null) context.startActivity(intent)
                                else Toast.makeText(context, "Shizuku belum terpasang", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Gagal membuka Shizuku", Toast.LENGTH_SHORT).show()
                            }
                        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(0)
                        } else {
                            viewModel.toggleShizukuState() 
                        }
                    }) {
                        Icon(
                            imageVector = if (shizukuConnected) AppIcons.Usb else AppIcons.Usb,
                            contentDescription = null,
                            tint = if (shizukuConnected) MaterialTheme.colorScheme.secondary else WarningOrange
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0F1318)) {
                NavigationTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { 
                            val icon = when(tab) {
                                NavigationTab.DASBOR -> AppIcons.Dashboard
                                NavigationTab.OPTIMASI -> AppIcons.Rocket
                                NavigationTab.LOG -> AppIcons.History
                                NavigationTab.DIAGNOSIS -> AppIcons.Analytics
                                NavigationTab.KEAMANAN -> AppIcons.Shield
                                NavigationTab.KEAMANAN -> AppIcons.Shield
                            }
                            Icon(icon, contentDescription = null)
                        },
                        label = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = Color.White.copy(alpha = 0.4f),
                            unselectedTextColor = Color.White.copy(alpha = 0.4f),
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            AnimatedContent(targetState = selectedTab, label = "") { tab ->
                when (tab) {
                    NavigationTab.DASBOR -> DashboardScreen(viewModel)
                    NavigationTab.OPTIMASI -> BoostScreen(viewModel)
                    NavigationTab.LOG -> LogsScreen(viewModel)
                    NavigationTab.DIAGNOSIS -> DiagnosticScreen(viewModel)
                    NavigationTab.KEAMANAN -> SecurityScreen()
                    NavigationTab.KEAMANAN -> SecurityScreen()
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: GameBoostViewModel) {
    val stats by viewModel.systemMetrics.collectAsStateWithLifecycle()
    val isBoostActive by viewModel.isBoostActive.collectAsStateWithLifecycle()
    val shizukuConnected by viewModel.shizukuConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile = profiles.find { it.isActive }
    
    val health by viewModel.healthStatus.collectAsStateWithLifecycle()
    val depState by viewModel.dependencyState.collectAsStateWithLifecycle()
    
    var dpiIndex by remember { mutableIntStateOf(4) }
    val currentPointerSpeed by viewModel.pointerSpeed.collectAsStateWithLifecycle()
    var pointerSpeedValue by remember { mutableFloatStateOf(currentPointerSpeed.toFloat()) }

    // Sincronizar slider al DPI real del dispositivo al entrar a la pantalla (solo una vez)
    LaunchedEffect(Unit) {
        val nearestIdx = DPI_STEPS.indices.minBy { kotlin.math.abs(DPI_STEPS[it] - stats.dpi) }
        dpiIndex = nearestIdx
    }

    LaunchedEffect(currentPointerSpeed) {
        pointerSpeedValue = currentPointerSpeed.toFloat()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(
            title = "Dependensi sistem",
            icon = AppIcons.Shield
        ) {
            val dep = depState
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DependencyRow("Shizuku", dep.shizuku.state.name, dep.shizuku.state == com.example.data.repository.DependencyState.Shizuku.ShizukuState.ON, AppIcons.Usb)
                DependencyRow("Aksesibilitas", dep.accessibility.state.name, dep.accessibility.state == com.example.data.repository.DependencyState.Accessibility.AccessibilityState.ACTIVE, AppIcons.Accessibility)
                DependencyRow("Baterai", dep.batteryOptimization.state.name, dep.batteryOptimization.state == com.example.data.repository.DependencyState.BatteryOptimization.BatteryState.UNRESTRICTED, AppIcons.BatteryCharge)
                DependencyRow("GameBoostService", dep.gameBoostService.state.name, dep.gameBoostService.state == com.example.data.repository.DependencyState.GameBoostService.ServiceState.RUNNING, AppIcons.Cpu)
            }
            if (health.restartCount > 0) {
                Text(
                    "Restart otomatis: ${health.restartCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarningOrange,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        SectionCard(
            title = "Konfigurasi saat ini",
            icon = AppIcons.Gear
        ) {
            ConfigRow("Profil", activeProfile?.name?.stripEmoji() ?: "Tidak ada", AppIcons.Gamepad)
            ConfigRow("DPI", "${stats.dpi}", AppIcons.Smartphone)
            ConfigRow("Penunjuk", "${currentPointerSpeed}/10", AppIcons.Mouse)
            ConfigRow("Animasi", stats.animationScale, AppIcons.Zap)
            ConfigRow("Refresh Rate", stats.refreshRate, AppIcons.Monitor)
            ConfigRow("Governor", stats.governor, AppIcons.Cpu)
            
            val externalDevicesConnected by viewModel.externalDevicesConnected.collectAsStateWithLifecycle()
            ConfigRow("Mobilador", if (externalDevicesConnected) "Terdeteksi" else "Tidak terdeteksi", AppIcons.Mouse)
        }

        SectionCard(
            title = "Auto-Boost Engine",
            subtitle = if (isBoostActive) "Optimasi aktif" else "Optimasi nonaktif",
            action = {
                Switch(
                    // testTag("boost_switch"): selector estable para instrumentation
                    // (UiAutomator vía testTagsAsResourceId) y tests Compose.
                    modifier = Modifier.testTag("boost_switch"),
                    checked = isBoostActive,
                    onCheckedChange = { 
                        viewModel.toggleBoost()
                        val intent = Intent(context, GameBoostService::class.java)
                        if (!isBoostActive) {
                            intent.action = GameBoostService.ACTION_START
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                            PreferenceManager.setServiceRunning(context, true)
                            // R1 (C5): boost ON manual → seguir al boost de nuevo (un
                            // request=false viejo no debe ocultar el overlay recién activado)
                            com.example.data.repository.GameBoostRepository
                                .getInstance(context).setOverlayRequested(null)
                        } else {
                            intent.action = GameBoostService.ACTION_STOP
                            context.startService(intent)
                            PreferenceManager.setServiceRunning(context, false)
                            // R1 (C5): la UI no escribe el overlay directamente — pide
                            // vía la proyección (el observador del servicio es el único
                            // writer de FPM.show/hide).
                            com.example.data.repository.GameBoostRepository
                                .getInstance(context).setOverlayRequested(false)
                        }
                    }
                )
            }
        ) {
            val isMobiladorActive by viewModel.isMobiladorActive.collectAsStateWithLifecycle()
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Mode Mobilador", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (isMobiladorActive) MaterialTheme.colorScheme.primary else Color.White)
                    Text("Mapper, scrcpy, dan perangkat periferal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = isMobiladorActive,
                    onCheckedChange = { viewModel.toggleMobilador() },
                    modifier = Modifier.scale(0.8f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(AppIcons.Thermo, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                    Text("${stats.cpuTemp.toInt()}°C", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(AppIcons.BatteryCharge, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    Text("${stats.batteryLevel}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

SectionCard(
            title = "Panel Manager",
            subtitle = "Game Panel: edge swipe, panel, HUD, bubble",
            icon = AppIcons.Grid
        ) {
            var panelOn by remember { mutableStateOf(com.example.gamepanel.PanelSettings.panelEnabled(context)) }
            AdvancedToggle(
                title = "Aktifkan Panel Manager",
                subtitle = if (panelOn) "Aktif: panel muncul saat game berjalan" else "Nonaktif: tidak ada panel/overlay sama sekali",
                checked = panelOn,
                onCheckedChange = {
                    panelOn = it
                    com.example.gamepanel.GamePanelController.setPanelEnabled(context, it)
                }
            )
        }

SectionCard(
            title = "Data Saver Game",
            subtitle = "Blokir data latar aplikasi lain saat boost",
            icon = AppIcons.Speed
        ) {
            var saverOn by remember {
                mutableStateOf(PreferenceManager.getPrefBoolean(context, com.example.manager.PerformanceTweaks.PREF_DATA_SAVER_ON_BOOST, true))
            }
            AdvancedToggle(
                title = "Data Saver saat boost",
                subtitle = "Sync/update aplikasi lain tidak berebut CPU & sinyal dengan game. Game dan app ini tetap online.",
                checked = saverOn,
                onCheckedChange = {
                    saverOn = it
                    PreferenceManager.setPrefBoolean(context, com.example.manager.PerformanceTweaks.PREF_DATA_SAVER_ON_BOOST, it)
                }
            )
        }

// Refresh inmediato de DependencyStateManager al volver de Settings (onResume),
        // sin esperar al intervalo de 15s. depState.accessibility es la fuente única de verdad.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshDependencyState()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // Onboarding dialog state
        var showOnboarding by remember {
            mutableStateOf(
                !UnifiedAccessibilityService.isServiceRunning &&
                !PreferenceManager.isAccessibilityOnboardingShown(context)
            )
        }

        // --- TARJETAS DE ESTADO SHIZUKU Y ACCESIBILIDAD ---
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatusCard(
                modifier = Modifier.weight(1f),
                title = "Shizuku",
                isActive = shizukuConnected,
                icon = AppIcons.Usb,
                onClick = { 
                    if (!Shizuku.pingBinder()) {
                        try {
                            val intent = context.packageManager.getLaunchIntentForPackage("rikka.shizuku")
                            if (intent != null) context.startActivity(intent)
                            else Toast.makeText(context, "Shizuku belum terpasang", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Gagal membuka Shizuku", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        viewModel.toggleShizukuState()
                    }
                }
            )

            StatusCard(
                modifier = Modifier.weight(1f),
                title = "Aksesibilitas",
                isActive = depState.accessibility.state == com.example.data.repository.DependencyState.Accessibility.AccessibilityState.ACTIVE,
                icon = AppIcons.Accessibility,
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: Exception) {}
                }
            )
        }

        // Accessibility status banner
        if (depState.accessibility.state != com.example.data.repository.DependencyState.Accessibility.AccessibilityState.ACTIVE) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Layanan nonaktif — ketuk kartu untuk mengaktifkan",
                         style = MaterialTheme.typography.labelSmall, color = WarningOrange)
                    Icon(AppIcons.ArrowRight, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Onboarding dialog
        if (!UnifiedAccessibilityService.isServiceRunning && !PreferenceManager.isAccessibilityOnboardingShown(context)) {
            AccessibilityOnboardingDialog(
                onDismiss = {
                    PreferenceManager.setAccessibilityOnboardingShown(context, true)
                }
            )
        }

        // --- BOTÓN DE RECONEXIÓN SHIZUKU ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, HairLine)
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { 
                        viewModel.toggleShizukuState()
                        Toast.makeText(context, "Menghubungkan ulang Shizuku...", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), contentColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Hubungkan ulang Shizuku", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- TARJETA DE JUEGO DETECTADO ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, HairLine),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), Color.Transparent)
                                )
                            )
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.Circle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Game terdeteksi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stats.activeGame ?: "Mencari...", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                            Text("Optimasi Aktif", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.quickClean() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, HairLine),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(AppIcons.Broom, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Bersihkan Cache", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.quickClean() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, HairLine),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(AppIcons.Zap, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Optimalkan RAM", fontSize = 11.sp)
                    }
                }
            }
        }

        // --- AJUSTE DE PANTALLA (DPI) ---
        SectionCard(title = "Pengaturan layar", icon = AppIcons.Smartphone) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("DPI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${DPI_STEPS[dpiIndex]}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = dpiIndex.toFloat(),
                onValueChange = { dpiIndex = it.roundToInt() },
                valueRange = 0f..(DPI_STEPS.lastIndex.toFloat()),
                steps = DPI_STEPS.size - 2,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary)
            )

            // Etiquetas de valores discretos
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DPI_STEPS.forEachIndexed { index, dpi ->
                    Text(
                        "$dpi",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (dpi == 280) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            dpi == 280 -> MaterialTheme.colorScheme.secondary
                            index == dpiIndex -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.alpha(if (dpi % 80 == 0) 1f else 0.7f)
                    )
                }
            }
            Text("Nilai preset. 280 ideal untuk ZTE Nubia dengan layar memanjang.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.setDpi(DPI_STEPS[dpiIndex]) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(AppIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                    Text("Terapkan DPI", fontWeight = FontWeight.ExtraBold, color = Color.Black)
                }
            }
        }

        // --- AJUSTE DE PUNTERO ---
        SectionCard(title = "Kecepatan penunjuk", icon = AppIcons.Sliders) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Kecepatan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${pointerSpeedValue.toInt()}/10", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            }
            Slider(
                value = pointerSpeedValue,
                onValueChange = { pointerSpeedValue = it },
                valueRange = 0f..10f,
                steps = 9,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.secondary)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Lambat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Cepat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Atur sensitivitas mouse atau touch eksternal.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.setPointerSpeed(pointerSpeedValue.toInt()) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), contentColor = MaterialTheme.colorScheme.secondary)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(AppIcons.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Terapkan kecepatan", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
fun DependencyRow(
    label: String,
    state: String,
    isActive: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.White)
        }
        Text(
            state,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isActive) MaterialTheme.colorScheme.secondary else WarningOrange
        )
    }
}

@Composable
fun StatusCard(modifier: Modifier, title: String, isActive: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, HairLine),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
            Text(if (isActive) "Aktif" else "Nonaktif", fontWeight = FontWeight.ExtraBold, color = if (isActive) MaterialTheme.colorScheme.secondary else WarningOrange, fontSize = 12.sp)
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            action?.invoke()
        }
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, HairLine),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun BoostScreen(viewModel: GameBoostViewModel) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val availableGovernors by viewModel.availableGovernors.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateProfileDialog(
            availableGovernors = availableGovernors,
            onDismiss = { showCreateDialog = false },
            onSave = { name, desc, gov, refresh, icon ->
                viewModel.addCustomProfile(name, desc, gov, refresh, icon)
                showCreateDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column {
                Text("Mesin inti", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Profil Performa", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        Text("Sistem Siap", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontSize = 8.sp)
                    }
                }
                Text("Optimalkan alokasi hardware untuk skenario game tertentu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            }
        }

        items(profiles) { profile ->
            ProfileCardCompact(profile) { viewModel.setActiveProfile(profile.id) }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HairLine, contentColor = Color.White),
                border = BorderStroke(1.dp, HairLine)
            ) {
                Icon(AppIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Profil Kustom", fontWeight = FontWeight.SemiBold)
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Pengaturan lanjutan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            val isAggressive by viewModel.isAggressiveOptimizationEnabled.collectAsStateWithLifecycle()
            val isThermalWatchdog by viewModel.isThermalWatchdogEnabled.collectAsStateWithLifecycle()
            val isAutoDetect by viewModel.isAutoDetectGamesEnabled.collectAsStateWithLifecycle()
            val isDeepSleep by viewModel.isDeepSleepEnabled.collectAsStateWithLifecycle()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12171D)),
                border = BorderStroke(1.dp, HairLine),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AdvancedToggle("Optimasi Agresif", "Paksa tutup aplikasi di latar belakang", isAggressive) { viewModel.toggleAggressiveOptimization() }
                    AdvancedToggle("Watchdog Termal", "Pantau dan cegah panas berlebih", isThermalWatchdog) { viewModel.toggleThermalWatchdog() }
                    AdvancedToggle("Deteksi Game Otomatis", "Aktifkan profil secara otomatis", isAutoDetect) { viewModel.toggleAutoDetectGames() }
                    AdvancedToggle("Optimasi Saat Layar Mati", "Hemat sumber daya saat layar mati", isDeepSleep) { viewModel.toggleDeepSleep() }
                }
            }
        }
    }
}

@Composable
fun AdvancedToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.scale(0.8f))
    }
}

@Composable
fun ConfigRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

// Color naranja para perfil activo (gaming/performance)
private val ActiveProfileOrange = Color(0xFFFF8A3D)

@Composable
fun ProfileCardCompact(profile: ProfileEntity, onClick: () -> Unit) {
    val isActive = profile.isActive
    val activeColor = ActiveProfileOrange
    val activeBackgroundAlpha = 0.25f // Static alpha for active profile icon background
    
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF20262F) else Color(0xFF12171D)
        ),
        border = BorderStroke(
            width = if (isActive) 2.dp else 1.dp,
            color = if (isActive) activeColor.copy(alpha = 0.6f) else HairLine
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Icono del perfil con fondo destacado si está activo
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isActive) activeColor.copy(alpha = activeBackgroundAlpha)
                            else HairLine
                        )
                        .then(
                            if (isActive) Modifier.border(1.5.dp, activeColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(profileIcon(profile.icon), contentDescription = null, tint = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
                
                Spacer(modifier = Modifier.width(14.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        profile.name.stripEmoji(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isActive) activeColor else Color.White
                    )
                    Text(
                        profile.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Badge "AKTIF" con animación
                if (isActive) {
                    Surface(
                        color = activeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, activeColor.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(activeColor)
                            )
                            Text(
                                "Aktif",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = activeColor,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(14.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ProfileTag(AppIcons.Sliders, profile.governor, isActive)
                ProfileTag(AppIcons.Monitor, "${profile.refreshRate} Hz", isActive)
            }
        }
    }
}

@Composable
fun ProfileTag(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, isActive: Boolean = false) {
    val tagColor = if (isActive) ActiveProfileOrange else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (isActive) Modifier.background(ActiveProfileOrange.copy(alpha = 0.1f))
                else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tagColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Tab 3 — ACTIVIDAD (evidencia real del boost, orientada al jugador)
//
// Regla epistemológica: cada indicador proviene de estado real —
// StateFlows del core (juego/boost/FSM/perfil) o del snapshot SSOT
// persistido (BoostSession schema v2). Sin métricas decorativas.
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun EvidenceStatusRow(label: String, value: String, alive: Boolean, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (alive) Color(0xFF4CC38A) else Color(0xFF546E7A))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (alive) Color(0xFF4CC38A) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EvidenceChip(text: String, good: Boolean) {
    Surface(
        color = if (good) Color(0xFF4CC38A).copy(alpha = 0.12f) else Color(0xFFFF9800).copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, (if (good) Color(0xFF4CC38A) else Color(0xFFFF9800)).copy(alpha = 0.3f))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (good) Color(0xFF4CC38A) else Color(0xFFFF9800)
        )
    }
}

/** "hace Xs/Xm/Xh" desde un epoch millis (HealthStatus.lastCheck). */
private fun relativeAgeFromEpoch(millis: Long): String {
    val age = (System.currentTimeMillis() - millis).coerceAtLeast(0)
    return when {
        age < 60_000 -> "${age / 1000} dtk lalu"
        age < 3_600_000 -> "${age / 60_000} mnt lalu"
        else -> "${age / 3_600_000} jam lalu"
    }
}

/** "hace Xs/Xm/Xh" a partir del timestamp HH:mm:ss del log, anclado al día actual. */
private fun relativeAge(timestamp: String): String {
    return try {
        val parts = timestamp.split(":").map { it.trim().toIntOrNull() ?: return timestamp }
        if (parts.size != 3) return timestamp
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, parts[0])
        cal.set(java.util.Calendar.MINUTE, parts[1])
        cal.set(java.util.Calendar.SECOND, parts[2])
        // Log de ayer (madrugada): si el parse quedó >1 min en el futuro, es de ayer.
        if (now - cal.timeInMillis < -60_000) cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val age = (now - cal.timeInMillis).coerceAtLeast(0)
        when {
            age < 60_000 -> "${age / 1000} dtk lalu"
            age < 3_600_000 -> "${age / 60_000} mnt lalu"
            else -> "${age / 3_600_000} jam lalu"
        }
    } catch (_: Exception) { timestamp }
}

/**
 * Categoría visual del evento a partir del TAG REAL del log.
 * Mapeo exclusivo de los 19 tags productivos existentes — no se inventan eventos.
 */
private fun activityCategory(tag: String): Triple<String, Color, androidx.compose.ui.graphics.vector.ImageVector> = when (tag) {
    "Optimizer", "GameMode", "Mobilador", "DPI", "Pointer" ->
        Triple("BOOST", Color(0xFFFF8A3D), AppIcons.Rocket)
    "SysTweaks", "NetworkOpt", "RamManager", "GamingDND" ->
        Triple("OPTIMASI", Color(0xFF4CC38A), AppIcons.Broom)
    else ->
        Triple("SISTEM", Color(0xFFD9B38C), AppIcons.Cpu)
}

@Composable
private fun ActivityFilterChip(label: String, dotColor: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Color(0xFFFF8A3D).copy(alpha = 0.18f) else Color(0xFF161B22).copy(alpha = 0.6f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (selected) Color(0xFFFF8A3D).copy(alpha = 0.5f) else HairLine)
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dotColor))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Color(0xFFFF8A3D) else Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ActivitySummaryCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.White.copy(alpha = 0.45f))
        Spacer(modifier = Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
fun LogsScreen(viewModel: GameBoostViewModel) {
    var activityFilter by remember { mutableStateOf("ALL") }
    val activeGame by viewModel.simulatedGame.collectAsStateWithLifecycle()
    val boostActive by viewModel.isBoostActive.collectAsStateWithLifecycle()
    val fsmState by viewModel.fsmState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val session by viewModel.sessionEvidence.collectAsStateWithLifecycle()
    val lastRestore by viewModel.lastRestoreReport.collectAsStateWithLifecycle()
    val recent by viewModel.recentActivity.collectAsStateWithLifecycle()
    val activeProfile = profiles.find { it.isActive }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Título + badge EN VIVO (mockup)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Aktivitas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4CC38A)))
            Spacer(modifier = Modifier.weight(1f))
            EvidenceChip(text = "Langsung", good = true)
        }
        Text("Bukti nyata Game Boost", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Resumen superior 3 columnas (mockup) — datos reales
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF1B2129),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFFFF8A3D).copy(alpha = 0.22f))
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                ActivitySummaryCell("Kejadian", "${recent.size}", Color.White, Modifier.weight(1f))
                Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.08f)))
                ActivitySummaryCell("Kejadian terakhir", recent.firstOrNull()?.let { relativeAge(it.timestamp) } ?: "—", Color(0xFFFF8A3D), Modifier.weight(1f))
                Box(modifier = Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.08f)))
                ActivitySummaryCell("Status mesin", if (boostActive) "AKTIF" else "NONAKTIF", if (boostActive) Color(0xFF4CC38A) else Color.White.copy(alpha = 0.5f), Modifier.weight(1f))
            }
        }

        // Filtros funcionales por categoría real de tag
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityFilterChip("Semua", Color(0xFFFF8A3D), activityFilter == "ALL") { activityFilter = "ALL" }
            ActivityFilterChip("Boost", Color(0xFFFF8A3D), activityFilter == "BOOST") { activityFilter = "BOOST" }
            ActivityFilterChip("Optimasi", Color(0xFF4CC38A), activityFilter == "OPT") { activityFilter = "OPT" }
            ActivityFilterChip("Sistem", Color(0xFFD9B38C), activityFilter == "SYS") { activityFilter = "SYS" }
        }

        // ── Evidencia de sesión (snapshot SSOT persistido — acciones reales) ──
        SectionCard(
            title = "Bukti sesi",
            icon = AppIcons.ClipboardCheck,
            subtitle = session?.let { "sesi ${it.sessionId?.takeLast(8) ?: "—"} · mulai ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it.updatedAt))}" }
        ) {
            if (session == null) {
                Text(
                    "Belum ada sesi boost tercatat. Aktifkan boost (manual atau dengan membuka game) untuk mengambil dan menerapkan perubahan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val s = session!!
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EvidenceChip(text = s.state.name, good = s.state == BoostSessionState.ACTIVE)
                    EvidenceChip(text = "${s.baselineCount} dicadangkan", good = true)
                    EvidenceChip(text = "${s.appliedCount} ditulis", good = s.appliedCount > 0)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Nilai yang diterapkan boost pada sesi ini:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                if (s.appliedEntries.isEmpty()) {
                    Text(
                        if (s.state == BoostSessionState.APPLYING) "Menerapkan…" else "Belum ada penulisan tercatat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    s.appliedEntries.take(8).forEach { (id, value) ->
                        Text(
                            "$id = $value",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                    if (s.appliedEntries.size > 8) {
                        Text(
                            "… dan ${s.appliedEntries.size - 8} lainnya",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Última restauración (reporte verificado por relectura) ──
        SectionCard(title = "Pemulihan terakhir", icon = AppIcons.Restart) {
            val rep = lastRestore
            if (rep == null) {
                Text(
                    "Belum ada pemulihan pada sesi aplikasi ini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val verified = rep.results.values.count { it == RestoreResult.RESTORE_VERIFIED }
                val skipped = rep.results.values.count { it == RestoreResult.RESTORE_SKIPPED }
                val conflicts = rep.results.values.count { it == RestoreResult.RESTORE_CONFLICT }
                val failed = rep.results.values.count { it == RestoreResult.RESTORE_FAILED }
                EvidenceChip(
                    text = if (rep.allOk) "Selesai dan terverifikasi" else "Ada kegagalan — pemulihan tertunda",
                    good = rep.allOk
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("$verified dipulihkan · $skipped sudah bernilai asli · $conflicts dipertahankan (diubah pengguna) · $failed gagal", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            }
        }

        // ── Timeline de eventos (mockup: rail + dots + chips) — 100% datos reales ──
        val filtered = if (activityFilter == "ALL") recent else recent.filter {
            activityCategory(it.tag).first == when (activityFilter) {
                "BOOST" -> "BOOST"; "OPT" -> "OPTIMASI"; else -> "SISTEM"
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            // Rail vertical degradado
            Canvas(modifier = Modifier.matchParentSize().padding(start = 11.dp, top = 8.dp, bottom = 8.dp)) {
                drawLine(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFFFF8A3D).copy(alpha = 0.5f), Color(0xFF4CC38A).copy(alpha = 0.3f), HairLine)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 2f
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filtered.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF161B22).copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Tidak ada catatan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                if (activityFilter == "ALL") "Belum ada kejadian pada sesi ini." else "Tidak ada kejadian pada kategori ini.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                filtered.forEach { log ->
                    val (catLabel, catColor, _) = activityCategory(log.tag)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Dot del timeline
                        Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF0F1318)).border(2.dp, catColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(catColor))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        // Tarjeta del evento con barra de acento
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF161B22).copy(alpha = 0.65f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(catColor))
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = catColor.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp),
                                            border = BorderStroke(1.dp, catColor.copy(alpha = 0.3f))
                                        ) {
                                            Text(
                                                catLabel,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = catColor
                                            )
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(relativeAge(log.timestamp), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(log.message.stripEmoji(), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticScreen(viewModel: GameBoostViewModel) {
    val context = LocalContext.current
    val health by viewModel.healthStatus.collectAsStateWithLifecycle()
    val deps by viewModel.dependencyState.collectAsStateWithLifecycle()
    val fsm by viewModel.fsmState.collectAsStateWithLifecycle()
    val activeGame by viewModel.simulatedGame.collectAsStateWithLifecycle()
    val sessionEvidence by viewModel.sessionEvidence.collectAsStateWithLifecycle()
    val autoDetect by viewModel.isAutoDetectGamesEnabled.collectAsStateWithLifecycle()
    val techLogs by viewModel.logs.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf("") }
    var shizukuReport by remember { mutableStateOf("") }

    fun runDiagnosis() {
        report = viewModel.getDiagnosticReport()
        shizukuReport = viewModel.getShizukuDiagnosis(context)
    }

    LaunchedEffect(Unit) { runDiagnosis() }

    // ── Los 5 estados de componentes provienen de fuentes reales ──
    // Shizuku: DependencyStateManager (sondeo real) · Watchdog/Service: WatchdogManager
    // Accessibility: DependencyStateManager · Detector: FSM + toggle · FSM: GameSessionManager
    val shizukuReady = deps.shizuku.state == com.example.data.repository.DependencyState.Shizuku.ShizukuState.ON
    val a11yActive = deps.accessibility.state == com.example.data.repository.DependencyState.Accessibility.AccessibilityState.ACTIVE
    val serviceRunning = health.serviceAlive
    val detectorOk = autoDetect || activeGame != null
    val componentsReady = listOf(shizukuReady, a11yActive, serviceRunning, detectorOk, fsm != FsmState.DEGRADED && fsm != FsmState.RECOVERING).count { it }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ── Banner global: derivado del conteo real de componentes ──
        val allOk = componentsReady == 5
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = if (allOk) Color(0xFF4CC38A).copy(alpha = 0.08f) else WarningOrange.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, (if (allOk) Color(0xFF4CC38A) else WarningOrange).copy(alpha = 0.3f))
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (allOk) AppIcons.ShieldTouch else AppIcons.Alert,
                    contentDescription = null,
                    tint = if (allOk) Color(0xFF4CC38A) else WarningOrange,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        if (allOk) "Sistem berjalan normal" else "Sistem mengalami penurunan",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (allOk) Color(0xFF4CC38A) else WarningOrange
                    )
                    Text(
                        "$componentsReady/5 komponen siap",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // ── MÓDULOS DE ARQUITECTURA: los 5 componentes con estado real ──
        SectionCard(
            title = "Modul arsitektur",
            subtitle = "$componentsReady/5 siap",
            icon = AppIcons.Cpu
        ) {
            ComponentCard("Shizuku", if (shizukuReady) "Tersedia" else "Tidak tersedia", shizukuReady, AppIcons.Usb)
            Spacer(modifier = Modifier.height(8.dp))
            ComponentCard("Watchdog", if (serviceRunning) "AKTIF" else "Berhenti", serviceRunning, AppIcons.ShieldTouch)
            Spacer(modifier = Modifier.height(8.dp))
            ComponentCard("Game Detection", if (detectorOk) "Mendeteksi" else "Dinonaktifkan", detectorOk, AppIcons.Gamepad)
            Spacer(modifier = Modifier.height(8.dp))
            ComponentCard("Accessibility", if (a11yActive) "AKTIF" else "NONAKTIF", a11yActive, AppIcons.Accessibility)
            Spacer(modifier = Modifier.height(8.dp))
            ComponentCard(
                "Boost Engine (FSM)",
                when (fsm) {
                    FsmState.GAME_ACTIVE -> "AKTIF — GAME TERDETEKSI"
                    FsmState.READY -> "READY"
                    FsmState.INITIALIZING -> "MENGINISIALISASI"
                    FsmState.DEGRADED -> "MENURUN"
                    FsmState.RECOVERING -> "DALAM PEMULIHAN"
                },
                fsm == FsmState.GAME_ACTIVE || fsm == FsmState.READY,
                AppIcons.Zap
            )
        }

        // ── SHIZUKU RUNTIME: solo campos con fuente real (diagnose()) ──
        SectionCard(title = "Shizuku runtime", subtitle = if (shizukuReady) "Tersedia" else "Tidak tersedia", icon = AppIcons.Usb) {
            EvidenceStatusRow(
                "Status layanan",
                if (shizukuReady) "aktif" else "tidak ada sinyal",
                alive = shizukuReady,
                detail = deps.shizuku.detail.ifBlank { null }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                shizukuReport.ifBlank { "Belum ada diagnosis" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Ember,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = { runDiagnosis() }, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Periksa layanan", style = MaterialTheme.typography.labelMedium)
            }
        }

        // ── WATCHDOG MONITOR: HealthStatus real (estado + reinicios + última comprobación) ──
        SectionCard(title = "WATCHDOG MONITOR", subtitle = if (serviceRunning) "AKTIF" else "Berhenti", icon = AppIcons.ShieldTouch) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("STATUS", if (serviceRunning) "Stabil" else "Berhenti", good = serviceRunning, modifier = Modifier.weight(1f))
                MiniStat("RESTART", "${health.restartCount}", good = health.restartCount == 0, modifier = Modifier.weight(1f))
                MiniStat("CEK TERAKHIR", relativeAgeFromEpoch(health.lastCheck), good = true, modifier = Modifier.weight(1f))
            }
        }

        // ── BOOST ENGINE: FSM + sesión persistente (SSOT) ──
        SectionCard(title = "BOOST ENGINE (FSM)", subtitle = fsm.name, icon = AppIcons.Zap) {
            EvidenceStatusRow("Game di latar depan", activeGame ?: "tidak ada", alive = activeGame != null)
            Spacer(modifier = Modifier.height(8.dp))
            EvidenceStatusRow(
                "Sesi saat ini",
                when (sessionEvidence?.state) {
                    BoostSessionState.ACTIVE -> "AKTIF — ${sessionEvidence?.baselineCount ?: 0} key dicadangkan"
                    BoostSessionState.APPLYING -> "MENERAPKAN PERUBAHAN"
                    BoostSessionState.BASELINE_CAPTURED -> "BASELINE TERCATAT"
                    BoostSessionState.RESTORING -> "MEMULIHKAN"
                    BoostSessionState.RESTORED -> "DIPULIHKAN (sesi terakhir)"
                    BoostSessionState.RECOVERY_REQUIRED -> "RECOVERY DIPERLUKAN"
                    BoostSessionState.IDLE, null -> "tidak ada sesi aktif"
                },
                alive = sessionEvidence?.state == BoostSessionState.ACTIVE,
                detail = sessionEvidence?.sessionId?.let { "sesi $it" }
            )
            Spacer(modifier = Modifier.height(8.dp))
            EvidenceStatusRow(
                "Pemulihan",
                if (sessionEvidence?.state == BoostSessionState.RECOVERY_REQUIRED) "RECOVERY TERTUNDA" else "tidak ada yang tertunda",
                alive = sessionEvidence?.state != BoostSessionState.RECOVERY_REQUIRED
            )
        }

        // ── DIAGNÓSTICO DEL SISTEMA: checklist real (3 checks con fuente) ──
        SectionCard(title = "DIAGNOSIS SISTEM", subtitle = "pemeriksaan langsung", icon = AppIcons.ClipboardCheck) {
            EvidenceStatusRow("Layanan boost", if (health.serviceAlive) "berjalan" else "berhenti", alive = health.serviceAlive)
            Spacer(modifier = Modifier.height(8.dp))
            EvidenceStatusRow("Tautan aksesibilitas", if (a11yActive) "aktif" else "nonaktif", alive = a11yActive)
            Spacer(modifier = Modifier.height(8.dp))
            EvidenceStatusRow("Baterai tanpa batasan", if (health.batteryUnrestricted) "ok" else "dibatasi", alive = health.batteryUnrestricted)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = { runDiagnosis() }, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.ClipboardCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("JALANKAN PEMERIKSAAN CEPAT", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                report.ifBlank { "Belum ada diagnosis" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Ember,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
        }

        // ── REGISTRO TÉCNICO: preview real (3 líneas del log existente) ──
        SectionCard(
            title = "CATATAN TEKNIS SISTEM",
            subtitle = "${techLogs.size} kejadian",
            icon = AppIcons.History
        ) {
            if (techLogs.isEmpty()) {
                Text(
                    "Tidak ada catatan pada sesi ini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            } else {
                techLogs.take(3).forEach { log ->
                    Row(modifier = Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            when (log.level) {
                                "ERROR" -> "[ERR]"
                                "WARN" -> "[WRN]"
                                else -> "[INF]"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = when (log.level) {
                                "ERROR" -> ErrorRed
                                "WARN" -> WarningOrange
                                else -> Ember
                            }
                        )
                        Text(
                            "${log.timestamp} ${log.message.stripEmoji()}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Tarjeta compacta de componente (grid del mockup): nombre + estado real + dot. */
@Composable
private fun ComponentCard(name: String, status: String, ok: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (ok) Color(0xFF4CC38A).copy(alpha = 0.05f) else WarningOrange.copy(alpha = 0.05f),
                RoundedCornerShape(10.dp)
            )
            .border(1.dp, (if (ok) Color(0xFF4CC38A) else WarningOrange).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (ok) Ember else WarningOrange, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.labelSmall, color = if (ok) Color(0xFF4CC38A) else WarningOrange)
        }
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (ok) Color(0xFF4CC38A) else WarningOrange))
    }
}

/** Stat compacta estilo mockup (Watchdog): label + valor. */
@Composable
private fun MiniStat(label: String, value: String, good: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (good) Color(0xFF4CC38A) else WarningOrange
        )
    }
}

@Composable
fun CreateProfileDialog(
    availableGovernors: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var governor by remember { mutableStateOf(availableGovernors.firstOrNull() ?: "schedutil") }
    var refreshRate by remember { mutableFloatStateOf(60f) }
    var icon by remember { mutableStateOf("gamepad") }
    var hyperTouch by remember { mutableStateOf(true) }
    var lowLatency by remember { mutableStateOf(false) }
    var masterFilter by remember { mutableStateOf(true) }
    
    var showGovDropdown by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1318)), border = BorderStroke(1.dp, HairLine)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("GameBoost", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Kalibrasi Performa", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nama Profil") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = HairLine))
                        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Deskripsi") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = HairLine))
                    }
                    CalibrationSection(title = "Kontrol Governor", icon = AppIcons.Sliders) {
                        Text("Pilih perilaku penskalaan CPU.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        
                        Box {
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { showGovDropdown = true },
                                color = HairLine,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, HairLine)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(governor, style = MaterialTheme.typography.bodyMedium)
                                    Icon(AppIcons.ArrowDown, contentDescription = null)
                                }
                            }
                            
                            DropdownMenu(
                                expanded = showGovDropdown,
                                onDismissRequest = { showGovDropdown = false },
                                modifier = Modifier.background(Color(0xFF161B22))
                            ) {
                                availableGovernors.forEach { gov ->
                                    DropdownMenuItem(
                                        text = { Text(gov, color = Color.White) },
                                        onClick = {
                                            governor = gov
                                            showGovDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    CalibrationSection(title = "Mesin Layar", icon = AppIcons.Screenshot) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Target Refresh Rate", style = MaterialTheme.typography.bodySmall)
                            Text("${refreshRate.toInt()}Hz", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(value = refreshRate, onValueChange = { refreshRate = it }, valueRange = 60f..144f, steps = 3, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary))
                        CalibrationToggle(label = "Hyper-Touch Sampling", checked = hyperTouch, onCheckedChange = { hyperTouch = it })
                    }
                    CalibrationSection(title = "Jalur Jaringan", icon = AppIcons.Wifi) {
                        CalibrationToggle(label = "Mode Latensi Rendah", checked = lowLatency, onCheckedChange = { lowLatency = it })
                        CalibrationToggle(label = "Pembatasan Data", checked = true, onCheckedChange = {})
                    }
                    CalibrationSection(title = "Modul Lanjutan", icon = AppIcons.Puzzle) {
                        CalibrationToggle(label = "Master Filter (AI)", checked = masterFilter, onCheckedChange = { masterFilter = it })
                        CalibrationToggle(label = "Afinitas CPU", checked = true, onCheckedChange = {})
                    }
                }
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { onSave(name.ifBlank { "Profil Baru" }, description.ifBlank { "Konfigurasi kustom" }, governor, refreshRate.toInt().toString(), icon) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color(0xFF1A0F06))) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Simpan Profil", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(AppIcons.ArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Kembali", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.clickable { onDismiss() })
                }
            }
        }
    }
}

@Composable
fun CalibrationSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
        }
        content()
    }
}

@Composable
fun CalibrationToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.scale(0.8f))
    }
}

@Composable
fun HealthBadge(label: String, isAlive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isAlive) Color(0xFF4CC38A) else ErrorRed)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isAlive) Color.White else Color.White.copy(alpha = 0.6f)
        )
        Icon(
            if (isAlive) AppIcons.CheckCircle else AppIcons.XCircle,
            contentDescription = null,
            tint = if (isAlive) Ok else ErrorRed,
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
fun AccessibilityOnboardingDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1318)),
            border = BorderStroke(1.dp, HairLine)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Izin Aksesibilitas diperlukan",
                         style = MaterialTheme.typography.headlineSmall,
                         fontWeight = FontWeight.Bold,
                         color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Di beberapa perangkat ZTE/MyOS, mungkin perlu memeriksa pengaturan baterai dan autostart agar layanan ini tetap aktif.",
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(AppIcons.BatteryCharge, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                            Text("Pengaturan → Baterai → Tanpa batasan untuk GameBoost Pro",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurface)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(AppIcons.Play, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                            Text("Pengaturan → Autostart → Aktif untuk GameBoost Pro",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Mengerti", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }
}
