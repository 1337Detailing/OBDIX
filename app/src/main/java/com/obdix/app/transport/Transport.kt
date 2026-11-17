package com.obdix.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

interface ByteTransport {
    suspend fun send(bytes: ByteArray)
    suspend fun receive(): ByteArray
    suspend fun close()
}

enum class LinkType { BLUETOOTH, BLE, WIFI, USB, KDCAN }

data class ConnectionSpec(
    val type: LinkType,
    val address: String = "",
    val port: Int = 35000,
    val usbDeviceId: Int = -1,
    val baudRate: Int = 38400
)

object TransportFactory {
    @SuppressLint("MissingPermission")
    suspend fun open(context: Context, spec: ConnectionSpec): ByteTransport {
        return when (spec.type) {
            LinkType.BLUETOOTH -> {
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                    ?: error("Bluetooth indisponible")
                ClassicTransport.connect(adapter, spec.address)
            }
            LinkType.BLE -> {
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                    ?: error("Bluetooth indisponible")
                BleTransport.connect(context, adapter.getRemoteDevice(spec.address))
            }
            LinkType.WIFI -> NetworkTransport.connect(spec.address, spec.port)
            LinkType.USB, LinkType.KDCAN -> UsbTransport.connect(context, spec.usbDeviceId, spec.baudRate)
        }
    }
}

private class StreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val dispose: () -> Unit
) : ByteTransport {
    @Volatile private var closed = false

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (closed) error("Connexion fermée")
        output.write(bytes)
        output.flush()
    }

    override suspend fun receive(): ByteArray {
        while (!closed) {
            val available = withContext(Dispatchers.IO) { input.available() }
            if (available > 0) return withContext(Dispatchers.IO) {
                val buf = ByteArray(minOf(available, 2048))
                val count = input.read(buf)
                if (count <= 0) throw IllegalStateException("Connexion terminée")
                buf.copyOf(count)
            }
            delay(15)
        }
        error("Connexion fermée")
    }

    override suspend fun close() {
        closed = true
        withContext(Dispatchers.IO) { dispose() }
    }
}

private object ClassicTransport {
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @SuppressLint("MissingPermission")
    suspend fun connect(adapter: BluetoothAdapter, address: String): ByteTransport =
        withContext(Dispatchers.IO) {
            val device = adapter.getRemoteDevice(address)
            val socket = device.createRfcommSocketToServiceRecord(sppUuid)
            try {
                adapter.cancelDiscovery()
                socket.connect()
                StreamTransport(socket.inputStream, socket.outputStream) { socket.close() }
            } catch (t: Throwable) {
                socket.close()
                throw t
            }
        }
}

private object NetworkTransport {
    suspend fun connect(host: String, port: Int): ByteTransport = withContext(Dispatchers.IO) {
        require(host.isNotBlank() && port in 1..65535)
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.tcpNoDelay = true
            StreamTransport(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
        } catch (t: Throwable) {
            socket.close()
            throw t
        }
    }
}

private class UsbTransport(private val port: UsbSerialPort) : ByteTransport {
    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        port.write(bytes, 3000)
        Unit
    }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArray(2048)
        val count = port.read(buffer, 200)
        if (count <= 0) byteArrayOf() else buffer.copyOf(count)
    }

    override suspend fun close() = withContext(Dispatchers.IO) { port.close() }

    companion object {
        suspend fun connect(context: Context, id: Int, baud: Int): UsbTransport = withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
                .firstOrNull { it.device.deviceId == id }
                ?: error("Adaptateur USB introuvable")
            require(manager.hasPermission(driver.device)) {
                "Autorisation USB requise : reconnecter puis accepter la demande"
            }
            val connection = manager.openDevice(driver.device) ?: error("Impossible d'ouvrir USB")
            val port = driver.ports.first()
            try {
                port.open(connection)
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                UsbTransport(port)
            } catch (t: Throwable) {
                runCatching { port.close() }
                connection.close()
                throw t
            }
        }
    }
}

/** ELM BLE varies by vendor; only GATT UART characteristics are accepted. */
private class BleTransport(
    private val device: BluetoothDevice,
    private val context: Context
) : ByteTransport {
    private val connected = CompletableDeferred<Unit>()
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private lateinit var gatt: BluetoothGatt
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeAck: CompletableDeferred<Int>? = null

    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val uartService = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    val reason = IllegalStateException("BLE : état $status")
                    if (!connected.isCompleted) connected.completeExceptionally(reason)
                    incoming.close(reason)
                }
                newState == BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val reason = IllegalStateException("BLE déconnecté")
                    if (!connected.isCompleted) connected.completeExceptionally(reason)
                    incoming.close(reason)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connected.completeExceptionally(IllegalStateException("Services BLE indisponibles"))
                return
            }
            val services = g.services.sortedByDescending { it.uuid == uartService }
            for (service in services) {
                val notify = service.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }
                val write = service.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                if (notify != null && write != null) {
                    notifyCharacteristic = notify
                    writeCharacteristic = write
                    g.setCharacteristicNotification(notify, true)
                    val descriptor = notify.getDescriptor(cccdUuid)
                    if (descriptor == null) {
                        connected.completeExceptionally(IllegalStateException("BLE UART sans descripteur CCCD"))
                        return
                    }
                    val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    val started = if (Build.VERSION.SDK_INT >= 33)
                        g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
                    else {
                        @Suppress("DEPRECATION")
                        descriptor.value = value
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(descriptor)
                    }
                    if (!started) connected.completeExceptionally(IllegalStateException("BLE CCCD refusé"))
                    return
                }
            }
            connected.completeExceptionally(IllegalStateException("Pas de service UART BLE compatible"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (!connected.isCompleted) {
                if (status == BluetoothGatt.GATT_SUCCESS) connected.complete(Unit)
                else connected.completeExceptionally(IllegalStateException("Notifications BLE non actives"))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (c.uuid == notifyCharacteristic?.uuid) incoming.trySend(value.copyOf())
        }

        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = c.value
            if (c.uuid == notifyCharacteristic?.uuid && bytes != null) incoming.trySend(bytes.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun start() {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(12000) { connected.await() }
        } catch (t: Throwable) {
            gatt.close()
            throw t
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun send(bytes: ByteArray) {
        val ch = writeCharacteristic ?: error("BLE non prêt")
        val writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        for (chunk in bytes.toList().chunked(20)) {
            val payload = chunk.toByteArray()
            val ack = CompletableDeferred<Int>()
            writeAck = ack
            val status = if (Build.VERSION.SDK_INT >= 33)
                gatt.writeCharacteristic(ch, payload, writeType) == BluetoothGatt.GATT_SUCCESS
            else {
                @Suppress("DEPRECATION")
                ch.value = payload
                ch.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
            if (!status) throw IllegalStateException("Écriture BLE refusée")
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                check(withTimeout(4000) { ack.await() } == BluetoothGatt.GATT_SUCCESS) { "BLE write échoué" }
            } else delay(35)
        }
        writeAck = null
    }

    override suspend fun receive(): ByteArray = incoming.receive()

    @SuppressLint("MissingPermission")
    override suspend fun close() {
        incoming.close()
        if (::gatt.isInitialized) {
            gatt.disconnect()
            gatt.close()
        }
    }

    companion object {
        suspend fun connect(context: Context, device: BluetoothDevice): ByteTransport {
            val link = BleTransport(device, context.applicationContext)
            link.start()
            return link
        }
    }
}
