package com.example.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.example.permissions.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredWifiDirectPeer(
    val deviceName: String,
    val deviceAddress: String,
    val status: Int
) {
    val isConnected: Boolean get() = status == WifiP2pDevice.CONNECTED
    val isInvited: Boolean get() = status == WifiP2pDevice.INVITED
}

class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val _wifiDirectEnabled = MutableStateFlow(false)
    val wifiDirectEnabled: StateFlow<Boolean> = _wifiDirectEnabled.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredWifiDirectPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredWifiDirectPeer>> = _discoveredPeers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    var onGroupOwnerConnected: ((hostIp: String, isGroupOwner: Boolean) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    _wifiDirectEnabled.value = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestPeersSafely()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    if (networkInfo?.isConnected == true) {
                        manager?.requestConnectionInfo(ensureChannel()) { info ->
                            _connectionInfo.value = info
                            val hostIp = info?.groupOwnerAddress?.hostAddress
                            if (info != null && info.groupFormed && !hostIp.isNullOrBlank()) {
                                onGroupOwnerConnected?.invoke(hostIp, info.isGroupOwner)
                            }
                        }
                    } else {
                        _connectionInfo.value = null
                    }
                }
            }
        }
    }

    private fun ensureChannel(): WifiP2pManager.Channel? {
        if (channel == null && manager != null) {
            channel = manager.initialize(context.applicationContext, Looper.getMainLooper()) {
                channel = null
            }
        }
        return channel
    }

    fun register() {
        if (receiverRegistered || manager == null) return
        ensureChannel()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.applicationContext.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(onFailureReason: ((Int) -> Unit)? = null) {
        if (!PermissionHelper.snapshot(context).discoveryReady) {
            onFailureReason?.invoke(-1)
            return
        }
        val mgr = manager ?: return
        val ch = ensureChannel() ?: return
        _isScanning.value = true
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestPeersSafely()
            }

            override fun onFailure(reason: Int) {
                Log.w("WifiDirectManager", "discoverPeers failed: $reason")
                _isScanning.value = false
                onFailureReason?.invoke(reason)
            }
        })
    }

    fun stopDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        _isScanning.value = false
        mgr.stopPeerDiscovery(ch, null)
    }

    @SuppressLint("MissingPermission")
    fun requestPeersSafely() {
        if (!PermissionHelper.snapshot(context).discoveryReady) return
        val mgr = manager ?: return
        val ch = ensureChannel() ?: return
        mgr.requestPeers(ch) { peerList ->
            val mapped = peerList.deviceList.map { device ->
                DiscoveredWifiDirectPeer(
                    deviceName = device.deviceName.ifBlank { device.deviceAddress },
                    deviceAddress = device.deviceAddress,
                    status = device.status
                )
            }
            _discoveredPeers.value = mapped
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String, onResult: (Boolean) -> Unit) {
        if (!PermissionHelper.snapshot(context).discoveryReady) {
            onResult(false)
            return
        }
        val mgr = manager ?: run { onResult(false); return }
        val ch = ensureChannel() ?: run { onResult(false); return }

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
        }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true)
            override fun onFailure(reason: Int) {
                Log.w("WifiDirectManager", "connect failed: $reason")
                onResult(false)
            }
        })
    }

    fun disconnect() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.cancelConnect(ch, null)
        mgr.removeGroup(ch, null)
        _connectionInfo.value = null
    }
}
