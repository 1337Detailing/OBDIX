package com.obdix.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obdix.app.core.*
import com.obdix.app.bmw.BmwResponseState
import com.obdix.app.transport.ConnectionSpec
import com.obdix.app.transport.LinkType
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ObdixApp() }
    }
}

private enum class Tab(val label: String) {
    CONNECT("Connexion"), DTC("Défauts"), LIVE("Temps réel"),
    CODING("Codage"), SERVICE("Services"), SETTINGS("Réglages")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ObdixApp(model: ObdViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val night = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (night) darkColorScheme() else lightColorScheme()
    var tab by remember { mutableStateOf(Tab.CONNECT) }

    MaterialTheme(colorScheme = colors, shapes = Shapes(
        medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("OBDIX", fontWeight = FontWeight.Bold)
                            Text(if (state.connected) state.protocolName else "Diagnostic automobile",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        SuggestionChip(onClick = { tab = Tab.CONNECT },
                            label = { Text(if (state.connected) "Connecté" else "Hors ligne") })
                        Spacer(Modifier.width(10.dp))
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { page ->
                        NavigationBarItem(
                            selected = tab == page, onClick = { tab = page },
                            alwaysShowLabel = false,
                            icon = {
                                Icon(when (page) {
                                    Tab.CONNECT -> Icons.Outlined.Bluetooth
                                    Tab.DTC -> Icons.Outlined.WarningAmber
                                    Tab.LIVE -> Icons.Outlined.ShowChart
                                    Tab.CODING -> Icons.Outlined.Tune
                                    Tab.SERVICE -> Icons.Outlined.Build
                                    Tab.SETTINGS -> Icons.Outlined.Settings
                                }, contentDescription = page.label)
                            },
                            label = { Text(page.label, maxLines = 1) }
                        )
                    }
                }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (state.busy || state.downloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                }
                when (tab) {
                    Tab.CONNECT -> ConnectPage(state, model)
                    Tab.DTC -> FaultPage(state, model)
                    Tab.LIVE -> LivePage(state, model)
                    Tab.CODING -> UnavailablePage("Codage BMW",
                        "Les écritures ne sont pas activées. Il faut un backend EDIABAS validé, les définitions exactes de l'ECU, une sauvegarde et une restauration vérifiées.",
                        "Un ELM327 standard ne permet pas le codage Carly.")
                    Tab.SERVICE -> UnavailablePage("Réinitialisations",
                        "La remise à zéro CBS (vidange, plaquettes et entretien) exige des jobs spécifiques à chaque constructeur et calculateur.",
                        "Aucune commande de service générique n'est exécutée.")
                    Tab.SETTINGS -> SettingsPage(state, model)
                }
                Spacer(Modifier.height(16.dp))
                if (state.message.isNotBlank()) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(state.message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun ConnectPage(s: AppState, model: ObdViewModel) {
    var type by remember { mutableStateOf(LinkType.BLUETOOTH) }
    var chosen by remember(type) { mutableStateOf<String?>(null) }
    var host by remember { mutableStateOf("192.168.0.10") }
    var tcpPort by remember { mutableStateOf("35000") }
    var serialSpeed by remember { mutableStateOf("38400") }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) model.refreshDevices(type)
        else model.notify("Autorisation Bluetooth refusée")
    }

    fun refresh() {
        val requested = if (Build.VERSION.SDK_INT >= 31) {
            if (type == LinkType.BLE)
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            else arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (type == LinkType.BLE) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else emptyArray()
        if (requested.isNotEmpty() && requested.any {
                context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }) permissionLauncher.launch(requested)
        else model.refreshDevices(type)
    }

    PageTitle("Connexion", "Sélectionnez l'adaptateur et son interface.")
    if (s.connected) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Session active", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(s.linkName)
                Text(s.adapterName, style = MaterialTheme.typography.bodySmall)
                Text(s.protocolName, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { model.disconnect() }) { Text("Déconnecter") }
            }
        }
        return
    }
    Text("Interface", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkType.entries.forEach { link ->
            FilterChip(
                selected = type == link,
                onClick = { type = link; chosen = null },
                label = { Text(when (link) {
                    LinkType.BLUETOOTH -> "BT"
                    LinkType.BLE -> "BLE"
                    LinkType.WIFI -> "Wi-Fi"
                    LinkType.USB -> "USB"
                    LinkType.KDCAN -> "K+DCAN"
                }) }
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    when (type) {
        LinkType.WIFI -> {
            OutlinedTextField(host, { host = it }, label = { Text("Adresse IP") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(tcpPort, { tcpPort = it.filter(Char::isDigit) },
                label = { Text("Port TCP") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Text("Connectez d'abord le téléphone au réseau Wi-Fi de l'adaptateur.",
                style = MaterialTheme.typography.bodySmall)
        }
        else -> {
            OutlinedButton(onClick = { refresh() }, enabled = !s.busy) {
                Text(if (type == LinkType.BLE) "Rechercher BLE" else "Afficher les adaptateurs")
            }
            if (s.scanning) Text("Recherche en cours…", style = MaterialTheme.typography.bodySmall)
            val list = if (type == LinkType.USB) s.usbDevices else s.devices
            if (list.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(when (type) {
                    LinkType.BLUETOOTH -> "Associez votre ELM327 dans les réglages Android."
                    LinkType.BLE -> "Les modules BLE ELM doivent exposer un service UART compatible."
                    LinkType.USB -> "Branchez un adaptateur ELM327 USB-série avec câble OTG."
                    LinkType.KDCAN -> "Utilisez un câble BMW K+DCAN USB (FTDI recommandé), moteur arrêté, contact établi."
                    else -> ""
                }, style = MaterialTheme.typography.bodySmall)
            }
            list.forEach { device ->
                Row(Modifier.fillMaxWidth().clickable { chosen = device.address }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen == device.address,
                        onClick = { chosen = device.address })
                    Column {
                        Text(device.label, style = MaterialTheme.typography.bodyMedium)
                        Text(device.address, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (type == LinkType.USB) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(serialSpeed, { serialSpeed = it.filter(Char::isDigit) },
                    label = { Text("Bauds (ELM)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = {
            val spec = when (type) {
                LinkType.WIFI -> ConnectionSpec(type, host.trim(), tcpPort.toIntOrNull() ?: 35000)
                LinkType.USB -> ConnectionSpec(type, usbDeviceId = chosen?.toIntOrNull() ?: -1,
                    baudRate = serialSpeed.toIntOrNull() ?: 38400)
                LinkType.KDCAN -> ConnectionSpec(type, usbDeviceId = chosen?.toIntOrNull() ?: -1,
                    baudRate = 115200)
                else -> ConnectionSpec(type, address = chosen.orEmpty())
            }
            model.connect(spec)
        },
        enabled = !s.busy && (type == LinkType.WIFI && host.isNotBlank() ||
            type != LinkType.WIFI && chosen != null),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Connecter") }
    Spacer(Modifier.height(16.dp))
    Text(if (type == LinkType.KDCAN) "K+DCAN natif : pas de commandes AT ELM327. Identifier les ECU dans Défauts."
        else "La détection automatique couvre les protocoles pris en charge par le firmware ELM.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FaultPage(s: AppState, model: ObdViewModel) {
    var confirm by remember { mutableStateOf(false) }
    if (s.bmwMode) {
        PageTitle("BMW E-Series", "Identification expérimentale des calculateurs via K+DCAN.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.scanBmwFaults() }, enabled = s.connected && !s.busy) {
                Text("Scanner défauts BMW")
            }
            OutlinedButton(onClick = { model.identifyBmw() },
                enabled = s.connected && !s.busy) { Text("Identifier") }
        }
        Spacer(Modifier.height(14.dp))
        s.bmwFaultReports.forEach { ecu ->
            ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(ecu.name, style = MaterialTheme.typography.titleMedium)
                    Text(ecu.detail, style = MaterialTheme.typography.bodySmall)
                    ecu.faults.forEach { fault ->
                        Spacer(Modifier.height(5.dp))
                        Text("${fault.code} · ${fault.state}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold)
                        if (fault.warningRequested) Text("Voyant demandé par le calculateur",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (ecu.status == BmwResponseState.INVALID &&
                        ecu.responseHex.isNotBlank()) {
                        Text(ecu.responseHex, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (s.bmwIdentities.isEmpty() && s.bmwFaultReports.isEmpty()) {
            NoticeCard("Lecture seule", "Lecture expérimentale des défauts BMW sur les principaux ECU E9x. Les descriptions détaillées, les CBS et le codage nécessitent des définitions adaptées à chaque variante.")
        }
        s.bmwIdentities.forEach { ecu ->
            ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(ecu.name, style = MaterialTheme.typography.titleMedium)
                    Text("Adresse %02X · %s".format(ecu.address,
                        when (ecu.status) {
                            BmwResponseState.POSITIVE -> "Réponse positive"
                            BmwResponseState.NEGATIVE -> "Réponse négative"
                            BmwResponseState.NO_REPLY -> "Injoignable"
                            BmwResponseState.INVALID -> "Non reconnue"
                        }), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(ecu.detail, style = MaterialTheme.typography.bodySmall)
                    if (ecu.responseHex.isNotBlank())
                        Text(ecu.responseHex, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        return
    }
    PageTitle("Défauts", "Calculateurs moteur/émissions OBD-II uniquement.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { model.scanFaults() }, enabled = s.connected && !s.busy) {
            Text("Scanner")
        }
        OutlinedButton(onClick = { confirm = true }, enabled = s.connected && !s.busy) {
            Text("Effacer")
        }
    }
    Spacer(Modifier.height(14.dp))
    if (s.dtcs.isEmpty()) {
        NoticeCard("Aucun code affiché", "Lancez un scan pour relever les codes mémorisés, en attente et permanents.")
    } else {
        s.dtcs.forEach { item ->
            ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.code, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text(item.status.label, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(item.summary, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(5.dp))
                    Text(item.advice, style = MaterialTheme.typography.bodySmall)
                    Text("Gravité et état actif à confirmer avec un outil constructeur.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Effacer les défauts ?") },
        text = { Text("La commande OBD-II 04 peut effacer les données figées et remet des moniteurs émissions à zéro. Elle n'efface pas un calculateur BMW individuellement. Ne continuez qu'après avoir relevé les codes.") },
        confirmButton = {
            TextButton(onClick = { confirm = false; model.clearFaultsConfirmed() }) { Text("Effacer") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } }
    )
}

@Composable
private fun LivePage(s: AppState, model: ObdViewModel) {
    PageTitle("Temps réel", "Seuls les paramètres déclarés compatibles sont interrogés.")
    Button(onClick = { model.togglePolling() }, enabled = s.connected && !s.bmwMode,
        modifier = Modifier.fillMaxWidth()) {
        Text(if (s.polling) "Arrêter les mesures" else "Démarrer les mesures")
    }
    Spacer(Modifier.height(18.dp))
    if (!s.connected || s.bmwMode) {
        NoticeCard("En attente de liaison OBD-II", "Connectez un ELM327 pour les PID standard ; le K+DCAN utilise la page BMW.")
        return
    }
    StandardPids.catalog.filter { it.pid in s.supported }.forEach { definition ->
        val value = s.measures[definition.pid]
        val history = s.history[definition.pid].orEmpty()
        ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = definition.pid in s.selectedPids,
                        onCheckedChange = { model.togglePid(definition.pid) })
                    Text(definition.label, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (value == null) "—" else
                        "${"%.1f".format(Locale.FRANCE, value.value)} ${value.unit}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                if (history.size > 1) Sparkline(history)
            }
        }
    }
    if (s.supported.none { pid -> StandardPids.catalog.any { it.pid == pid } })
        NoticeCard("PID absents", "L'ECU n'annonce pas les capteurs standard présents dans OBDIX.")
}

@Composable
private fun Sparkline(history: List<Double>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp)) {
        val min = history.minOrNull() ?: 0.0
        val max = history.maxOrNull() ?: 1.0
        val range = (max - min).coerceAtLeast(0.001)
        val path = Path()
        history.forEachIndexed { index, measurement ->
            val x = index.toFloat() / (history.lastIndex.coerceAtLeast(1)) * size.width
            val y = size.height - ((measurement - min) / range).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun UnavailablePage(title: String, body: String, footnote: String) {
    PageTitle(title, "Fonction constructeur non intégrée.")
    NoticeCard("Non disponible", body)
    Spacer(Modifier.height(12.dp))
    Text(footnote, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NoticeCard(title: String, body: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SettingsPage(s: AppState, model: ObdViewModel) {
    PageTitle("Réglages", "Versions et limites techniques.")
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { model.checkUpdate() }, enabled = !s.busy) {
                Text("Vérifier les mises à jour")
            }
            s.update?.let { update ->
                Spacer(Modifier.height(8.dp))
                Text("v${update.version.major}.${update.version.minor}.${update.version.patch} disponible")
                Button(onClick = { model.installUpdate() }, enabled = !s.downloading) {
                    Text("Installer la mise à jour")
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    NoticeCard("Compatibilité",
        "ELM327 : défauts émissions et mesures OBD-II. Pour BMW E-series, les autres calculateurs, le codage et les remises à zéro requièrent une implémentation EDIABAS validée.")
    Spacer(Modifier.height(12.dp))
    Text("GitHub : github.com/1337Detailing/OBDIX",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
