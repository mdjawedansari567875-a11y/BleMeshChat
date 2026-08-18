package com.carfam.blemeshchat

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleMeshService : Service() {

    companion object {
        private const val TAG = "BleMeshService"

        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val ACTION_MESSAGE_EVENT = "com.carfam.blemeshchat.MESSAGE_EVENT"
        const val ACTION_PEER_COUNT_CHANGED = "com.carfam.blemeshchat.PEER_COUNT_CHANGED"
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_TARGET_ID = "target_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_PEER_COUNT = "peer_count"

        private const val MAX_SEEN_CACHE = 500
        private const val NOTIF_CHANNEL_ID = "ble_mesh_service"
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): BleMeshService = this@BleMeshService
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private lateinit var prefs: SharedPreferences
    private lateinit var localId: String

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null

    private val subscribedCentrals = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val outgoingGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val seenIds = LinkedHashSet<String>()

    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ble_mesh_chat", Context.MODE_PRIVATE)
        localId = prefs.getString("local_id", null) ?: run {
            val id = "user-" + (1000..9999).random()
            prefs.edit().putString("local_id", id).apply()
            id
        }
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        createNotificationChannel()
    }

    fun getLocalId(): String = localId

    @SuppressLint("MissingPermission")
    fun startMeshing() {
        if (isRunning) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available/enabled")
            return
        }
        isRunning = true
        startForeground(1, buildForegroundNotification())
        setupGattServer()
        startAdvertising()
        startScanning()
    }

    @SuppressLint("MissingPermission")
    fun stopMeshing() {
        isRunning = false
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
        outgoingGatts.values.forEach { try { it.close() } catch (_: Exception) {} }
        outgoingGatts.clear()
        subscribedCentrals.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun sendMessage(text: String) {
        val msg = MeshMessage.createMessage(localId, text)
        seenIds.add(msg.id)
        broadcastToUi(msg)
        relay(msg, excludeAddress = null)
    }

    fun sendEdit(targetId: String, newText: String) {
        val msg = MeshMessage.createEdit(localId, targetId, newText)
        seenIds.add(msg.id)
        broadcastToUi(msg)
        relay(msg, excludeAddress = null)
    }

    fun sendDeleteForEveryone(targetId: String) {
        val msg = MeshMessage.createDelete(localId, targetId)
        seenIds.add(msg.id)
        broadcastToUi(msg)
        relay(msg, excludeAddress = null)
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccd)
        service.addCharacteristic(characteristic)
        messageCharacteristic = characteristic
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedCentrals.remove(device)
                notifyPeerCountChanged()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid == CHAR_UUID) {
                MeshMessage.fromWire(value)?.let { msg -> handleIncoming(msg, fromAddress = device.address) }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedCentrals.add(device)
                } else {
                    subscribedCentrals.remove(device)
                }
                notifyPeerCountChanged()
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.e(TAG, "Advertise failed: $errorCode") }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (outgoingGatts.containsKey(device.address)) return
            device.connectGatt(this@BleMeshService, false, gattClientCallback)
        }
        override fun onScanFailed(errorCode: Int) { Log.e(TAG, "Scan failed: $errorCode") }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.requestMtu(517)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    outgoingGatts.remove(gatt.device.address)
                    gatt.close()
                    notifyPeerCountChanged()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(CHAR_UUID) ?: return
            gatt.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
            outgoingGatts[gatt.device.address] = gatt
            notifyPeerCountChanged()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHAR_UUID) {
                MeshMessage.fromWire(characteristic.value)?.let { msg ->
                    handleIncoming(msg, fromAddress = gatt.device.address)
                }
            }
        }
    }

    private fun handleIncoming(msg: MeshMessage, fromAddress: String?) {
        synchronized(seenIds) {
            if (seenIds.contains(msg.id)) return
            seenIds.add(msg.id)
            if (seenIds.size > MAX_SEEN_CACHE) {
                seenIds.iterator().apply { next(); remove() }
            }
        }
        broadcastToUi(msg)
        if (msg.ttl > 0) {
            relay(msg.copy(ttl = msg.ttl - 1), excludeAddress = fromAddress)
        }
    }

    @SuppressLint("MissingPermission")
    private fun relay(msg: MeshMessage, excludeAddress: String?) {
        val wire = msg.toWire()
        val char = messageCharacteristic
        if (char != null) {
            for (device in subscribedCentrals) {
                if (device.address == excludeAddress) continue
                char.value = wire
                gattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
        for ((address, gatt) in outgoingGatts) {
            if (address == excludeAddress) continue
            val service = gatt.getService(SERVICE_UUID) ?: continue
            val characteristic = service.getCharacteristic(CHAR_UUID) ?: continue
            characteristic.value = wire
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun broadcastToUi(msg: MeshMessage) {
        val intent = Intent(ACTION_MESSAGE_EVENT).apply {
            putExtra(EXTRA_TYPE, msg.type)
            putExtra(EXTRA_ID, msg.id)
            putExtra(EXTRA_SENDER, msg.senderId)
            putExtra(EXTRA_TARGET_ID, msg.targetId)
            putExtra(EXTRA_TEXT, msg.text)
            putExtra(EXTRA_TIMESTAMP, msg.timestamp)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // Counts distinct physical devices, not distinct connections (a peer might
    // be connected to us both as central and peripheral at once).
    private fun notifyPeerCountChanged() {
        val addresses = HashSet<String>()
        subscribedCentrals.forEach { addresses.add(it.address) }
        addresses.addAll(outgoingGatts.keys)
        val intent = Intent(ACTION_PEER_COUNT_CHANGED).apply {
            putExtra(EXTRA_PEER_COUNT, addresses.size)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL_ID, "Mesh Chat", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Mesh Chat active")
            .setContentText("Discovering nearby devices via Bluetooth")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopMeshing()
        super.onDestroy()
    }
}
