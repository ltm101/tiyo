package com.koyo.screenwarden

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * BLE 连接层：扫描 / 连接 / GATT 读写 / Notify 订阅。
 *
 * 只负责"发现 + 建立链路 + 健康探活"，不含任何设备业务语义——
 * service/characteristic UUID、写入内容都由调用方（驱动描述）给出。
 * 前台扫描/连接，不做后台常驻 BLE。
 */
object BleConnector {

    data class ScannedDevice(val name: String, val address: String, val rssi: Int)

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return mgr.adapter?.isEnabled == true
    }

    fun isBluetoothEnabled(context: Context): Boolean =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.isEnabled == true

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ── 扫描 ──

    private var activeScan: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startScan(
        context: Context,
        timeoutMs: Long = 10_000,
        onDevice: (ScannedDevice) -> Unit,
        onDone: () -> Unit
    ) {
        val leScanner = adapter(context)?.bluetoothLeScanner
            ?: return onDone()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: "未知设备"
                onDevice(ScannedDevice(name, result.device.address, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                onDone()
            }
        }
        activeScan = cb
        try {
            leScanner.startScan(cb)
        } catch (_: Exception) {
            activeScan = null
            return onDone()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            stopScan(context)
            onDone()
        }, timeoutMs)
    }

    @SuppressLint("MissingPermission")
    fun stopScan(context: Context) {
        val cb = activeScan ?: return
        try {
            adapter(context)?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
        activeScan = null
    }

    // ── 连接 ──

    /**
     * 连接一个 BLE 设备并发现服务。返回的 [BleConnection] 负责后续读写/订阅。
     */
    @SuppressLint("MissingPermission")
    fun connect(
        context: Context,
        device: BluetoothDevice,
        serviceUuid: String?,
        writeUuid: String?,
        notifyUuid: String?,
        listener: BleConnection.Listener
    ): BleConnection = BleConnection(context, device, serviceUuid, writeUuid, notifyUuid, listener)
        .also { it.connect() }
}

/** 单个 BLE 连接的 GATT 状态机封装。 */
class BleConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val serviceUuid: String?,
    private val writeUuid: String?,
    private val notifyUuid: String?,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onNotify(data: ByteArray)
        fun onError(message: String)
        fun onDisconnected()
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isClosed = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> listener.onDisconnected()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("服务发现失败（$status）")
                return
            }
            val svc = serviceUuid?.let { runCatching { g.getService(UUID.fromString(it)) }.getOrNull() }
            val write = writeUuid?.let { svc?.getCharacteristic(UUID.fromString(it)) }
            val notify = notifyUuid?.let { svc?.getCharacteristic(UUID.fromString(it)) }
            if (write == null && writeUuid != null) {
                listener.onError("未找到写入特征")
                return
            }
            writeChar = write
            if (notify != null) subscribeNotify(g, notify)
            listener.onConnected()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            listener.onNotify(characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) listener.onError("写入失败（$status）")
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) listener.onError("读取失败（$status）")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        gatt = device.connectGatt(context, false, callback)
    }

    /** 写入一条指令字节流。 */
    @SuppressLint("MissingPermission")
    fun write(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ 返回状态码，0 即 BluetoothStatusCodes.SUCCESS
                g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.value = data
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isClosed = true
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNotify(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        try {
            g.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )
            cccd?.let { d ->
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        } catch (_: Exception) {
        }
    }
}
