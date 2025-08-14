package com.example.ecgraph

//@file:Suppress("SpellCheckingInspection")

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.util.ArrayDeque
import java.util.UUID

class RecordActivity : AppCompatActivity() {
    // ================== Constants ==================
    private companion object {
        private const val WINDOW_SEC = 8f
        private const val RENDER_FPS = 45L
        private const val DRAIN_PER_FRAME = 500
    }

    // ================== UI / Chart ==================
    private lateinit var ecgChart: LineChart
    private lateinit var dataSet: LineDataSet
    private var t = 0f

    // ================== BLE ==================
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null

    // Project UUIDs (đổi ở cả firmware + app nếu thay)
    private val ServiceUuid = UUID.fromString("0000ECG0-0000-1000-8000-00805F9B34FB")
    private val CharUuid    = UUID.fromString("0000ECG1-0000-1000-8000-00805F9B34FB")

    // ================== Runtime ==================
    private val ui = Handler(Looper.getMainLooper())

    // Parser buffer (demo khung gói AA 55 | len | payload | crc1)
    private val parseBuffer = ByteArray(4096)
    private var parseLen = 0

    // Simple circular buffer for samples
    private val queue = ArrayDeque<Int>(8192)

    // ===== Permissions =====
    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPerm(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            has(Manifest.permission.BLUETOOTH_SCAN)
        else
            has(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasConnectPerm(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            has(Manifest.permission.BLUETOOTH_CONNECT)
        else true

    private val blePerms: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasAllPerms(): Boolean = blePerms.all { has(it) }

    @SuppressLint("MissingPermission")
    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_SCAN) { res ->
        val granted = res.values.all { it }
        if (granted) ensureBluetoothEnabledThenStart() else showDesc("Need BLE permissions to scan")
    }

    @SuppressLint("MissingPermission")
    private val reqEnableBt = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ){ ensureBluetoothEnabledThenStart() }

    // ================== Lifecycle ==================
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ecg_record)

        ecgChart = findViewById(R.id.ecgChart)
        setupChart()
        showDesc("Press Start to begin recording")

        findViewById<android.widget.Button>(R.id.btnStart).setOnClickListener {
            if (!hasAllPerms()) reqPerms.launch(blePerms) else ensureBluetoothEnabledThenStart()
        }

        findViewById<android.widget.Button>(R.id.btnStop).setOnClickListener @androidx.annotation.RequiresPermission(
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) {
            stopRendering(); stopScan(); cleanupGatt(); storeReceivedData()
            startActivity(Intent(this, AnalysisActivity::class.java)); finish()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onPause() { super.onPause(); stopRendering(); stopScan() }
    @SuppressLint("MissingPermission")
    override fun onDestroy() { super.onDestroy(); cleanupGatt() }

    // ================== Chart setup/render ==================
    private fun setupChart() {
        ecgChart.apply {
            setTouchEnabled(true)
            setScaleEnabled(false)
            setDrawGridBackground(false)
            description.isEnabled = false
            legend.isEnabled = false
            axisRight.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = Color.BLACK
                axisLineColor = Color.BLACK
                granularity = 0.1f
                setLabelCount(10, true)
            }

            axisLeft.apply {
                setDrawGridLines(true)
                textColor = Color.BLACK
                axisLineColor = Color.BLACK
                granularity = 0.1f
                setLabelCount(8, true)
            }
        }

        dataSet = LineDataSet(mutableListOf(), "ECG").apply {
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 1.5f
            mode = LineDataSet.Mode.LINEAR
        }
        ecgChart.data = LineData(dataSet)
    }

    private val renderTick = object : Runnable {
        override fun run() {
            val raw = drainSamples(DRAIN_PER_FRAME)
            val down = raw.filterIndexed { idx, _ -> idx % 2 == 0 }

            down.forEach { v ->
                dataSet.addEntry(Entry(t, v.toFloat()))
                t += 1f / 500f // chỉnh theo sample-rate thực tế
            }

            val minX = t - WINDOW_SEC
            while (dataSet.entryCount > 0 && dataSet.getEntryForIndex(0).x < minX) {
                dataSet.removeEntry(dataSet.getEntryForIndex(0))
            }

            ecgChart.data.notifyDataChanged()
            ecgChart.notifyDataSetChanged()
            ecgChart.setVisibleXRangeMaximum(WINDOW_SEC)
            ecgChart.moveViewToX(t)

            ui.postDelayed(this, 1000L / RENDER_FPS)
        }
    }

    private fun startRendering() { ui.post(renderTick) }
    private fun stopRendering() { ui.removeCallbacksAndMessages(null) }

    // ================== Scan / Connect ==================
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun ensureBluetoothEnabledThenStart() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) { showDesc("Device has no Bluetooth"); return }
        if (!adapter.isEnabled) { reqEnableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        startScan(serviceUuid = ServiceUuid)
    }

    private fun ensureScanPermOrFail(): Boolean {
        if (!hasScanPerm()) { showDesc("Missing scan permission"); return false }
        return true
    }

    private fun ensureConnectPermOrFail(): Boolean {
        if (!hasConnectPerm()) { showDesc("Missing connect permission"); return false }
        return true
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startScan(serviceUuid: UUID? = null) {
        if (!ensureScanPermOrFail()) return
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: run { showDesc("No Bluetooth adapter"); return }
        scanner = bt.bluetoothLeScanner

        val filters = mutableListOf<ScanFilter>()
        if (serviceUuid != null) filters += ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        try {
            scanner?.startScan(filters, settings, scanCb)
            scanning = true
            showDesc("Scanning for devices…")
        } catch (_: SecurityException) {
            showDesc("Scan blocked by permission")
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopScan() {
        if (!scanning) return
        if (!hasScanPerm()) { scanning = false; return }
        try { scanner?.stopScan(scanCb) } catch (_: SecurityException) {}
        scanning = false
        showDesc("Scan stopped")
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            stopScan()
            if (!ensureConnectPermOrFail()) return
            try {
                gatt = device.connectGatt(this@RecordActivity, false, gattCb)
                showDesc("Connecting to ${device.address} …")
            } catch (_: SecurityException) { showDesc("Connect blocked by permission") }
        }

        override fun onScanFailed(errorCode: Int) { showDesc("Scan failed: $errorCode") }
    }

    // ================== GATT Callback ==================
    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { showDesc("Connect error: $status"); cleanupGatt(); return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    showDesc("Connected. Negotiating MTU…")
                    if (hasConnectPerm()) try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: SecurityException) {}
                    if (hasConnectPerm()) try { if (!g.requestMtu(185)) g.discoverServices() } catch (_: SecurityException) { if (hasConnectPerm()) try { g.discoverServices() } catch (_: SecurityException) {} }
                }
                BluetoothProfile.STATE_DISCONNECTED -> { showDesc("Disconnected"); cleanupGatt() }
            }
        }

        @SuppressLint("MissingPermission")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            showDesc("MTU=$mtu (status=$status). Discovering services…")
            if (hasConnectPerm()) try { gatt.discoverServices() } catch (_: SecurityException) {}
        }

        @SuppressLint("MissingPermission")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { showDesc("Discover failed: $status"); return }
            val svc = if (hasConnectPerm()) try { g.getService(ServiceUuid) } catch (_: SecurityException) { null } else null
            if (svc == null) { showDesc("Service not found"); return }
            val ch  = svc.getCharacteristic(CharUuid) ?: run { showDesc("Char not found"); return }

            if (!hasConnectPerm()) { showDesc("Missing connect permission"); return }
            try {
                val okLocal = g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                if (okLocal && cccd != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                } else {
                    showDesc("CCCD not found or local notify failed")
                }
            } catch (_: SecurityException) { showDesc("Notify blocked by permission") }
        }

        @SuppressLint("MissingPermission")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) { showDesc("Notifications enabled. Streaming…"); startRendering() }
            else showDesc("Enable notify failed: $status")
        }

        @SuppressLint("MissingPermission")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != CharUuid) return
            val data = ch.value ?: return

            // Append to parse buffer
            val cap = parseBuffer.size - parseLen
            val copy = minOf(cap, data.size)
            System.arraycopy(data, 0, parseBuffer, parseLen, copy)
            parseLen += copy

            // Parse frames: [0xAA, 0x55][len][payload ...][crc]
            var i = 0
            while (i + 4 <= parseLen) {
                val b0 = parseBuffer[i].toInt() and 0xFF
                val b1 = parseBuffer[i + 1].toInt() and 0xFF
                if (b0 == 0xAA && b1 == 0x55) {
                    val len = parseBuffer[i + 2].toInt() and 0xFF
                    if (i + 3 + len <= parseLen) {
                        val payloadStart = i + 3
                        val payloadEnd = payloadStart + len - 1 // last = CRC1 (demo)
                        // TODO: verify CRC over [payloadStart .. payloadEnd-1] vs parseBuffer[payloadEnd]
                        var p = payloadStart
                        while (p + 1 < payloadEnd) {
                            val lo = parseBuffer[p].toInt() and 0xFF
                            val hi = parseBuffer[p + 1].toInt()
                            val sample = (hi shl 8) or lo
                            enqueueSample(sample.toShort().toInt())
                            p += 2
                        }
                        i = payloadEnd + 1
                    } else break
                } else {
                    i += 1
                }
            }
            if (i > 0) {
                System.arraycopy(parseBuffer, i, parseBuffer, 0, parseLen - i)
                parseLen -= i
            }
        }
    }

    // ================== Helpers ==================
    private fun showDesc(msg: String) {
        runOnUiThread {
            ecgChart.apply {
                description.isEnabled = true
                description.text = msg
                description.textColor = Color.DKGRAY
                description.textSize = 12f
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun cleanupGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    @Synchronized private fun enqueueSample(v: Int) {
        if (queue.size >= 8000) queue.removeFirst()
        queue.addLast(v)
    }

    @Synchronized private fun drainSamples(max: Int): List<Int> {
        val out = ArrayList<Int>(max)
        repeat(minOf(max, queue.size)) { out += queue.removeFirst() }
        return out
    }

    private fun storeReceivedData() { /* TODO: save to file if needed */ }
}
