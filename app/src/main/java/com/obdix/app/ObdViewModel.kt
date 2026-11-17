package com.obdix.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.obdix.app.core.*
import com.obdix.app.bmw.BmwEcuIdentity
import com.obdix.app.bmw.BmwFaultReport
import com.obdix.app.bmw.BmwKdcSession
import com.obdix.app.bmw.BmwE90Addresses
import com.obdix.app.transport.ConnectionSpec
import com.obdix.app.transport.LinkType
import com.obdix.app.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DeviceOption(val label: String, val address: String)
data class ChartPoint(val pid: Int, val value: Double)
data class AppState(
    val busy: Boolean = false,
    val connected: Boolean = false,
    val linkName: String = "",
    val adapterName: String = "",
    val protocolName: String = "",
    val message: String = "Aucun adaptateur connecté",
    val devices: List<DeviceOption> = emptyList(),
    val usbDevices: List<DeviceOption> = emptyList(),
    val scanning: Boolean = false,
    val dtcs: List<Dtc> = emptyList(),
    val supported: Set<Int> = emptySet(),
    val selectedPids: Set<Int> = setOf(0x0C, 0x05, 0x04, 0x0B),
    val measures: Map<Int, Measurement> = emptyMap(),
    val history: Map<Int, List<Double>> = emptyMap(),
    val polling: Boolean = false,
    val update: AvailableUpdate? = null,
    val downloading: Boolean = false,
    val bmwMode: Boolean = false,
    val bmwIdentities: List<BmwEcuIdentity> = emptyList(),
    val bmwFaultReports: List<BmwFaultReport> = emptyList()
)

class ObdViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx = application.applicationContext
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var client: ElmClient? = null
    private var bmwSession: BmwKdcSession? = null
    private var pollJob: Job? = null
    private var scanCallback: ScanCallback? = null

    private fun bluetooth(): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    fun refreshDevices(type: LinkType) {
        try {
            when (type) {
                LinkType.BLUETOOTH -> {
                    val paired = bluetooth()?.bondedDevices.orEmpty().map {
                        DeviceOption(it.name ?: it.address, it.address)
                    }.sortedBy { it.label }
                    mutable.update { it.copy(devices = paired, message = if (paired.isEmpty())
                        "Associez l'adaptateur dans les réglages Bluetooth Android." else it.message) }
                }
                LinkType.USB, LinkType.KDCAN -> {
                    val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
                    val usb = UsbSerialProber.getDefaultProber().findAllDrivers(mgr).map {
                        DeviceOption("${it.device.productName ?: "USB série"} (${it.device.deviceId})",
                            it.device.deviceId.toString())
                    }
                    mutable.update { it.copy(usbDevices = usb) }
                }
                LinkType.BLE -> scanBle()
                LinkType.WIFI -> Unit
            }
        } catch (e: SecurityException) {
            notify("Permission Bluetooth nécessaire")
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanBle() {
        val scanner = bluetooth()?.bluetoothLeScanner ?: run {
            notify("Activez le Bluetooth")
            return
        }
        scanCallback?.let { scanner.stopScan(it) }
        val found = linkedMapOf<String, DeviceOption>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                val label = result.scanRecord?.deviceName ?: result.device.name ?: address
                found[address] = DeviceOption(label, address)
                mutable.update { it.copy(devices = found.values.sortedBy { d -> d.label }, scanning = true) }
            }

            override fun onScanFailed(errorCode: Int) {
                mutable.update { it.copy(scanning = false, message = "Scan BLE : erreur $errorCode") }
            }
        }
        scanCallback = callback
        mutable.update { it.copy(scanning = true, devices = emptyList()) }
        scanner.startScan(callback)
        viewModelScope.launch {
            delay(9000)
            runCatching { scanner.stopScan(callback) }
            if (scanCallback === callback) scanCallback = null
            mutable.update { it.copy(scanning = false) }
        }
    }

    fun connect(spec: ConnectionSpec) = runTask {
        disconnectInternal()
        if (spec.type == LinkType.USB || spec.type == LinkType.KDCAN) {
            val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = mgr.deviceList.values.firstOrNull { it.deviceId == spec.usbDeviceId }
                ?: error("USB introuvable")
            if (!mgr.hasPermission(device)) {
                val intent = Intent("${ctx.packageName}.USB_PERMISSION").setPackage(ctx.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                mgr.requestPermission(device, PendingIntent.getBroadcast(ctx, 3, intent, flags))
                notify("Acceptez l'autorisation USB, puis reconnectez.")
                return@runTask
            }
        }
        if (spec.type == LinkType.KDCAN) {
            check(spec.baudRate == 115200) { "K+DCAN nécessite 115200 bauds" }
            val usb = TransportFactory.open(ctx, spec)
            bmwSession = BmwKdcSession(usb)
            mutable.update {
                it.copy(connected = true, bmwMode = true,
                    linkName = "USB K+DCAN (115200)",
                    adapterName = "Port USB série",
                    protocolName = "BMW-FAST / D-CAN expérimental",
                    bmwIdentities = emptyList(), bmwFaultReports = emptyList(),
                    message = "Port USB ouvert. Identifier les calculateurs BMW pour vérifier la communication.")
            }
            return@runTask
        }
        val endpoint = ElmClient { TransportFactory.open(ctx, spec) }
        endpoint.connect()
        client = endpoint
        val supported = findSupported(endpoint)
        val negotiatedProtocol = endpoint.query("ATDP").replace(">", "").trim()
        mutable.update {
            it.copy(
                connected = true,
                bmwMode = false,
                linkName = when (spec.type) {
                    LinkType.WIFI -> "${spec.address}:${spec.port}"
                    LinkType.USB -> "USB (${spec.baudRate} baud)"
                    LinkType.KDCAN -> "BMW K+DCAN"
                    LinkType.BLE -> "BLE ${spec.address}"
                    LinkType.BLUETOOTH -> "Bluetooth ${spec.address}"
                },
                protocolName = negotiatedProtocol,
                adapterName = endpoint.adapter,
                supported = supported,
                dtcs = emptyList(),
                measures = emptyMap(),
                message = "Connexion établie"
            )
        }
    }

    private suspend fun findSupported(client: ElmClient): Set<Int> {
        val available = mutableSetOf<Int>()
        var base = 0x00
        repeat(4) {
            val result = ObdParser.supportedPids(client.query("01%02X".format(base)), base)
            available += result
            if (base + 0x20 !in result) return available
            base += 0x20
        }
        return available
    }

    fun disconnect() = viewModelScope.launch {
        mutable.update { it.copy(busy = true) }
        try { disconnectInternal() } finally { mutable.update { it.copy(busy = false) } }
    }

    private suspend fun disconnectInternal() {
        pollJob?.cancel()
        pollJob = null
        client?.close()
        client = null
        bmwSession?.close()
        bmwSession = null
        mutable.update { it.copy(connected = false, bmwMode = false, bmwIdentities = emptyList(), bmwFaultReports = emptyList(),
            polling = false, supported = emptySet(),
            message = "Déconnecté", measures = emptyMap(), history = emptyMap()) }
    }

    /** One read-only identification request per listed E90 ECU; no writes or unsafe retries. */
    fun identifyBmw() = runTask {
        val session = requireNotNull(bmwSession) { "Connectez le câble K+DCAN" }
        mutable.update { it.copy(bmwIdentities = emptyList()) }
        for ((address, name) in BmwE90Addresses.targets) {
            val result = session.identify(address, name)
            mutable.update { it.copy(bmwIdentities = it.bmwIdentities + result,
                message = "BMW : ${it.bmwIdentities.size + 1} calculateurs interrogés") }
        }
        mutable.update { it.copy(message = "Identification BMW terminée ; vérifier les réponses brutes.") }
    }

    fun scanBmwFaults() = runTask {
        val session = requireNotNull(bmwSession) { "Connectez le câble K+DCAN" }
        mutable.update { it.copy(bmwFaultReports = emptyList()) }
        for ((address, name) in BmwE90Addresses.targets) {
            val result = session.readFaults(address, name)
            mutable.update { it.copy(bmwFaultReports = it.bmwFaultReports + result,
                message = "BMW : ${it.bmwFaultReports.size + 1} calculateurs interrogés") }
        }
        val count = mutable.value.bmwFaultReports.sumOf { it.faults.size }
        mutable.update { it.copy(message = "Scan BMW terminé : $count code(s) valide(s) reçus.") }
    }

    fun scanFaults() = runTask {
        val endpoint = requireNotNull(client) { "Connectez un adaptateur" }
        val dtcs = listOf(
            DtcStatus.STORED to "03", DtcStatus.PENDING to "07",
            DtcStatus.PERMANENT to "0A"
        ).flatMap { (status, cmd) -> ObdParser.dtcs(endpoint.query(cmd), status) }
        mutable.update { it.copy(dtcs = dtcs, message = "${dtcs.size} défaut(s) OBD-II relevé(s)") }
    }

    fun clearFaultsConfirmed() = runTask {
        val endpoint = requireNotNull(client) { "Connectez un adaptateur" }
        stopPolling()
        val reply = endpoint.clearEmissionsConfirmed()
        check(!reply.contains("ERROR", ignoreCase = true) && !reply.contains("?", ignoreCase = true)) {
            "Effacement non confirmé par l'adaptateur"
        }
        mutable.update { it.copy(dtcs = emptyList(), message = "Commande d'effacement envoyée. Rescanner pour confirmer.") }
    }

    fun togglePid(pid: Int) {
        mutable.update {
            val pids = it.selectedPids.toMutableSet()
            if (!pids.add(pid)) pids.remove(pid)
            it.copy(selectedPids = pids)
        }
    }

    fun togglePolling() {
        if (pollJob?.isActive == true) {
            stopPolling()
            return
        }
        if (client == null) {
            notify("Connectez un adaptateur")
            return
        }
        pollJob = viewModelScope.launch {
            mutable.update { it.copy(polling = true) }
            while (isActive && client != null) {
                val chosen = StandardPids.catalog.filter {
                    it.pid in mutable.value.selectedPids && it.pid in mutable.value.supported
                }
                if (chosen.isEmpty()) {
                    notify("Aucun PID compatible sélectionné")
                    break
                }
                for (definition in chosen) {
                    if (!isActive) break
                    try {
                        val reply = requireNotNull(client).query("01%02X".format(definition.pid))
                        val measurement = ObdParser.parsePid(reply, definition) ?: continue
                        mutable.update { old ->
                            val history = old.history[definition.pid].orEmpty().takeLast(39) + measurement.value
                            old.copy(measures = old.measures + (definition.pid to measurement),
                                history = old.history + (definition.pid to history))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        notify("Mesure : ${e.message ?: "connexion perdue"}")
                        break
                    }
                    delay(120)
                }
            }
            mutable.update { it.copy(polling = false) }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        mutable.update { it.copy(polling = false) }
    }

    fun checkUpdate() = runTask {
        val update = UpdateManager.check(BuildConfig.VERSION_NAME.removeSuffix("-debug"))
        mutable.update { it.copy(update = update,
            message = if (update == null) "Application à jour (ou aucune Release publiée)"
            else "Nouvelle version disponible") }
    }

    fun installUpdate() = viewModelScope.launch {
        val update = mutable.value.update ?: return@launch
        mutable.update { it.copy(downloading = true) }
        try {
            val file = UpdateManager.download(ctx, update)
            UpdateManager.requestInstall(ctx, file)
        } catch (e: Exception) {
            notify("Mise à jour : ${e.message}")
        } finally {
            mutable.update { it.copy(downloading = false) }
        }
    }

    fun notify(message: String) = mutable.update { it.copy(message = message) }

    private fun runTask(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Erreur inconnue")
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCleared() {
        scanCallback?.let { callback -> runCatching { bluetooth()?.bluetoothLeScanner?.stopScan(callback) } }
        pollJob?.cancel()
        super.onCleared()
    }
}
