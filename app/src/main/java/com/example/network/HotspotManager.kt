package com.example.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.permissions.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

data class ActiveHotspotState(
    val isActive: Boolean = false,
    val ssid: String = "",
    val passphrase: String = "",
    val hostIp: String = "",
    val port: Int = TcpMeshTransport.MESH_PORT,
    val qrPayload: String = "",
    val isManualFallback: Boolean = false
)

data class DiscoveredLanPeer(
    val peerId: String,
    val displayName: String,
    val ipAddress: String,
    val port: Int,
    val lastSeenAt: Long = System.currentTimeMillis()
)

class HotspotManager(private val context: Context) {

    companion object {
        const val NSD_SERVICE_TYPE = "_offlinechat._tcp."
        const val UDP_BEACON_PORT = 8989
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val scope = CoroutineScope(Dispatchers.IO)

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var udpListenerJob: Job? = null
    private var udpBeaconJob: Job? = null

    private val _hotspotState = MutableStateFlow(ActiveHotspotState())
    val hotspotState: StateFlow<ActiveHotspotState> = _hotspotState.asStateFlow()

    private val _lanPeers = MutableStateFlow<List<DiscoveredLanPeer>>(emptyList())
    val lanPeers: StateFlow<List<DiscoveredLanPeer>> = _lanPeers.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startLocalHotspot(
        hostId: String,
        hostName: String,
        roomPin: String,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && PermissionHelper.snapshot(context).discoveryReady) {
            stopLocalHotspot()
            runCatching {
                wifiManager?.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        hotspotReservation = reservation
                        val (ssid, pass) = extractCredentials(reservation)
                        val localIp = resolveBestLocalIpv4() ?: "192.168.43.1"
                        val payload = HotspotJoinPayload(
                            ssid = ssid,
                            passphrase = pass,
                            hostIp = localIp,
                            port = TcpMeshTransport.MESH_PORT,
                            hostId = hostId,
                            hostName = hostName,
                            roomPin = roomPin
                        ).toQrString()

                        _hotspotState.value = ActiveHotspotState(
                            isActive = true,
                            ssid = ssid,
                            passphrase = pass,
                            hostIp = localIp,
                            port = TcpMeshTransport.MESH_PORT,
                            qrPayload = payload,
                            isManualFallback = false
                        )
                    }

                    override fun onStopped() {
                        _hotspotState.value = ActiveHotspotState()
                    }

                    override fun onFailed(reason: Int) {
                        activateManualHotspotShare(hostId, hostName, roomPin)
                        onError("Hotspot fallback mode ($reason)")
                    }
                }, Handler(Looper.getMainLooper()))
            }.onFailure {
                activateManualHotspotShare(hostId, hostName, roomPin)
                onError(it.message ?: "Hotspot unavailable")
            }
        } else {
            activateManualHotspotShare(hostId, hostName, roomPin)
        }
    }

    private fun activateManualHotspotShare(hostId: String, hostName: String, roomPin: String) {
        val localIp = resolveBestLocalIpv4() ?: "192.168.43.1"
        val payload = HotspotJoinPayload(
            ssid = "Device Hotspot",
            passphrase = "",
            hostIp = localIp,
            port = TcpMeshTransport.MESH_PORT,
            hostId = hostId,
            hostName = hostName,
            roomPin = roomPin
        ).toQrString()
        _hotspotState.value = ActiveHotspotState(
            isActive = true,
            ssid = "System Hotspot / Wi-Fi",
            passphrase = "Use device hotspot password",
            hostIp = localIp,
            port = TcpMeshTransport.MESH_PORT,
            qrPayload = payload,
            isManualFallback = true
        )
    }

    @Suppress("DEPRECATION")
    private fun extractCredentials(reservation: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = reservation.softApConfiguration
            val ssid = config.ssid ?: "OfflineChat_AP"
            val pass = config.passphrase ?: ""
            ssid to pass
        } else {
            val config = reservation.wifiConfiguration
            val ssid = config?.SSID ?: "OfflineChat_AP"
            val pass = config?.preSharedKey ?: ""
            ssid to pass
        }
    }

    fun stopLocalHotspot() {
        runCatching { hotspotReservation?.close() }
        hotspotReservation = null
        _hotspotState.value = ActiveHotspotState()
    }

    fun connectToHotspotWifi(
        ssid: String,
        passphrase: String,
        onConnected: () -> Unit,
        onFallbackNeeded: () -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ssid.isNotBlank() && ssid != "Device Hotspot") {
            val specifierBuilder = android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (passphrase.isNotBlank()) {
                specifierBuilder.setWpa2Passphrase(passphrase)
            }
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            runCatching {
                cm.requestNetwork(request, object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        cm.bindProcessToNetwork(network)
                        onConnected()
                    }

                    override fun onUnavailable() {
                        onFallbackNeeded()
                    }

                    override fun onLost(network: android.net.Network) {
                        runCatching { cm.bindProcessToNetwork(null) }
                    }
                })
            }.onFailure { onFallbackNeeded() }
        } else {
            onFallbackNeeded()
        }
    }

    fun startLanDiscoveryAndBeacon(localDeviceId: String, localDisplayName: String) {
        registerNsdService(localDeviceId, localDisplayName)
        discoverNsdServices(localDeviceId)
        startUdpBeacon(localDeviceId, localDisplayName)
        startUdpListener(localDeviceId)
    }

    fun stopLanDiscovery() {
        runCatching { registrationListener?.let { nsdManager?.unregisterService(it) } }
        runCatching { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        udpBeaconJob?.cancel()
        udpListenerJob?.cancel()
    }

    private fun registerNsdService(deviceId: String, displayName: String) {
        val mgr = nsdManager ?: return
        runCatching { registrationListener?.let { mgr.unregisterService(it) } }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "OC_${deviceId.takeLast(6)}_${displayName.take(12)}"
            serviceType = NSD_SERVICE_TYPE
            port = TcpMeshTransport.MESH_PORT
            setAttribute("id", deviceId)
            setAttribute("name", displayName)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun discoverNsdServices(localDeviceId: String) {
        val mgr = nsdManager ?: return
        runCatching { discoveryListener?.let { mgr.stopServiceDiscovery(it) } }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("offlinechat")) return
                @Suppress("DEPRECATION")
                mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val ip = resolved.host?.hostAddress ?: return
                        val peerId = resolved.attributes["id"]?.toString(Charsets.UTF_8) ?: ip
                        val peerName = resolved.attributes["name"]?.toString(Charsets.UTF_8)
                            ?: resolved.serviceName
                        if (peerId == localDeviceId) return
                        upsertLanPeer(DiscoveredLanPeer(peerId, peerName, ip, resolved.port))
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        discoveryListener = listener
        runCatching { mgr.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun startUdpBeacon(localDeviceId: String, localDisplayName: String) {
        udpBeaconJob?.cancel()
        udpBeaconJob = scope.launch {
            val socket = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return@launch
            socket.use { sock ->
                val payload = JSONObject().apply {
                    put("id", localDeviceId)
                    put("name", localDisplayName)
                    put("port", TcpMeshTransport.MESH_PORT)
                }.toString().toByteArray(Charsets.UTF_8)

                while (isActive) {
                    runCatching {
                        val broadcastTargets = linkedSetOf<InetAddress>()
                        broadcastTargets.add(InetAddress.getByName("255.255.255.255"))
                        runCatching {
                            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                                if (iface.isUp && !iface.isLoopback) {
                                    iface.interfaceAddresses.forEach { ifAddr ->
                                        ifAddr.broadcast?.let { broadcastTargets.add(it) }
                                    }
                                }
                            }
                        }
                        broadcastTargets.forEach { addr ->
                            runCatching {
                                sock.send(DatagramPacket(payload, payload.size, addr, UDP_BEACON_PORT))
                            }
                        }
                    }
                    delay(3000L)
                }
            }
        }
    }

    private fun startUdpListener(localDeviceId: String) {
        udpListenerJob?.cancel()
        udpListenerJob = scope.launch {
            val socket = runCatching { DatagramSocket(UDP_BEACON_PORT).apply { reuseAddress = true } }.getOrNull()
                ?: return@launch
            socket.use { sock ->
                val buffer = ByteArray(1024)
                while (isActive) {
                    runCatching {
                        val packet = DatagramPacket(buffer, buffer.size)
                        sock.receive(packet)
                        val senderIp = packet.address?.hostAddress ?: return@runCatching
                        val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        val peerId = json.optString("id")
                        val peerName = json.optString("name")
                        val port = json.optInt("port", TcpMeshTransport.MESH_PORT)
                        if (peerId.isNotBlank() && peerId != localDeviceId) {
                            upsertLanPeer(DiscoveredLanPeer(peerId, peerName.ifBlank { senderIp }, senderIp, port))
                        }
                    }.onFailure {
                        Log.d("HotspotManager", "UDP listen cycle: ${it.message}")
                    }
                }
            }
        }
    }

    private fun upsertLanPeer(peer: DiscoveredLanPeer) {
        val current = _lanPeers.value.toMutableList()
        val idx = current.indexOfFirst { it.peerId == peer.peerId || it.ipAddress == peer.ipAddress }
        if (idx >= 0) {
            current[idx] = peer
        } else {
            current.add(peer)
        }
        _lanPeers.value = current
    }

    fun resolveBestLocalIpv4(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val candidates = mutableListOf<Pair<Int, String>>()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val priority = when {
                            iface.name.contains("p2p", ignoreCase = true) -> 1
                            iface.name.contains("ap", ignoreCase = true) || ip.startsWith("192.168.43.") -> 2
                            iface.name.contains("wlan", ignoreCase = true) -> 3
                            else -> 4
                        }
                        candidates.add(priority to ip)
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second
        }.getOrNull()
    }
}
