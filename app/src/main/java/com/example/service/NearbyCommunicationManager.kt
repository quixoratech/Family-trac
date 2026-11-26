package com.example.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID

data class NearbyPeer(
    val id: String,
    val name: String,
    val transport: String, // "BLUETOOTH" or "WIFI_DIRECT"
    val address: String
)

data class NearbyPacket(
    val id: String,
    val senderName: String,
    val kind: String, // "CHAT", "TTT_MOVE", "SYNC_ACK"
    val payload: String,
    val timestampMs: Long
)

class NearbyCommunicationManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager?.adapter
    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var wifiChannel: WifiP2pManager.Channel? = null

    private var serverSocket: BluetoothServerSocket? = null
    private var receiverRegistered = false

    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val peers: StateFlow<List<NearbyPeer>> = _peers.asStateFlow()

    private val _packets = MutableStateFlow<List<NearbyPacket>>(emptyList())
    val packets: StateFlow<List<NearbyPacket>> = _packets.asStateFlow()

    private val _status = MutableStateFlow("Idle • Ready for close-range (~10m–30m) discovery")
    val status: StateFlow<String> = _status.asStateFlow()

    private val btReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val addr = device?.address ?: return
                val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Nearby Phone"
                val peer = NearbyPeer(id = addr, name = name, transport = "BLUETOOTH", address = addr)
                val current = _peers.value.filterNot { it.address == addr }
                _peers.value = current + peer
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscoveryAndServer() {
        _status.value = "Scanning nearby Bluetooth & Wi-Fi Direct peers..."
        // 1. Bluetooth bonded + discovery
        val adapter = btAdapter
        if (adapter != null && adapter.isEnabled) {
            runCatching {
                val bonded = adapter.bondedDevices.orEmpty().map {
                    NearbyPeer(
                        id = it.address,
                        name = it.name ?: "Paired Bluetooth Device",
                        transport = "BLUETOOTH",
                        address = it.address
                    )
                }
                _peers.value = (_peers.value + bonded).distinctBy { it.address }
                if (!receiverRegistered) {
                    ContextCompat.registerReceiver(
                        context.applicationContext,
                        btReceiver,
                        IntentFilter(BluetoothDevice.ACTION_FOUND),
                        ContextCompat.RECEIVER_EXPORTED
                    )
                    receiverRegistered = true
                }
                adapter.startDiscovery()
            }
            startBluetoothServer()
        } else {
            _status.value = "Bluetooth is off or unavailable; checking Wi-Fi Direct..."
        }

        // 2. Wi-Fi Direct discovery
        val p2p = wifiP2pManager
        if (p2p != null) {
            runCatching {
                if (wifiChannel == null) {
                    wifiChannel = p2p.initialize(context.applicationContext, Looper.getMainLooper(), null)
                }
                wifiChannel?.let { channel ->
                    p2p.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            p2p.requestPeers(channel) { list ->
                                val wifiPeers = list.deviceList.map { d: WifiP2pDevice ->
                                    NearbyPeer(
                                        id = d.deviceAddress,
                                        name = d.deviceName.ifBlank { "Wi-Fi Direct Device" },
                                        transport = "WIFI_DIRECT",
                                        address = d.deviceAddress
                                    )
                                }
                                _peers.value = (_peers.value + wifiPeers).distinctBy { it.address }
                            }
                        }

                        override fun onFailure(reason: Int) {
                            _status.value = "Bluetooth active • Wi-Fi Direct scan unavailable (code $reason)"
                        }
                    })
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
        if (serverSocket != null) return
        val adapter = btAdapter ?: return
        scope.launch {
            runCatching {
                val server = adapter.listenUsingRfcommWithServiceRecord("FamilyTrackBD", SERVICE_UUID)
                serverSocket = server
                while (true) {
                    val socket = server.accept()
                    handleIncomingSocket(socket)
                }
            }
        }
    }

    private fun handleIncomingSocket(socket: BluetoothSocket) {
        scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(socket.inputStream)).use { reader ->
                    val line = reader.readLine() ?: return@use
                    val obj = JSONObject(line)
                    val packet = NearbyPacket(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        senderName = obj.optString("senderName", "Nearby Family"),
                        kind = obj.optString("kind", "CHAT"),
                        payload = obj.optString("payload", ""),
                        timestampMs = obj.optLong("timestampMs", System.currentTimeMillis())
                    )
                    _packets.value = _packets.value + packet
                }
            }
            runCatching { socket.close() }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendBluetoothPacket(
        peerAddress: String,
        senderName: String,
        kind: String,
        payload: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val adapter = btAdapter ?: run {
            onResult(false, "Bluetooth adapter unavailable")
            return
        }
        scope.launch {
            try {
                runCatching { adapter.cancelDiscovery() }
                val device = adapter.getRemoteDevice(peerAddress)
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                val json = JSONObject().apply {
                    put("id", UUID.randomUUID().toString())
                    put("senderName", senderName)
                    put("kind", kind)
                    put("payload", payload)
                    put("timestampMs", System.currentTimeMillis())
                }.toString()
                PrintWriter(socket.outputStream, true).use { writer ->
                    writer.println(json)
                }
                socket.close()
                _packets.value = _packets.value + NearbyPacket(
                    id = UUID.randomUUID().toString(),
                    senderName = "You ($senderName)",
                    kind = kind,
                    payload = payload,
                    timestampMs = System.currentTimeMillis()
                )
                onResult(true, "Sent via Bluetooth")
            } catch (e: Exception) {
                onResult(false, e.localizedMessage ?: "Nearby Bluetooth device unreachable (~10m limit)")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { btAdapter?.cancelDiscovery() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (receiverRegistered) {
            runCatching { context.applicationContext.unregisterReceiver(btReceiver) }
            receiverRegistered = false
        }
    }

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("8a1e72c4-5f93-4e2b-9f1a-63b4e92f0101")
    }
}
