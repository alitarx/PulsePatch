package com.example.pulsepatch

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.*
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val serviceUUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
    private val characteristicUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")

    private val ecgData = List(12) { mutableStateListOf<Entry>() }
    private val ecgBuffer = ArrayDeque<Double>(5000)
    private val pendingECGSamples = mutableMapOf<Int, ByteArray>()

    private val sampleRate = 500.0
    private var lastValidRR: Double? = null
    private var currentBpm by mutableStateOf(0.0)
    private var globalIndex = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            BPMMonitorScreen()
        }

        startScan()
        startBpmUpdater()
    }

    @Composable
    fun BPMMonitorScreen() {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("12-Lead ECG Monitor", fontSize = 24.sp, modifier = Modifier.padding(8.dp))
            Text("💓 BPM: ${currentBpm.format(1)}", fontSize = 32.sp, modifier = Modifier.padding(8.dp))

            Button(onClick = { startScan() }) {
                Text("Start Scan")
            }
            Button(onClick = { disconnect() }) {
                Text("Disconnect")
            }

            LazyColumn {
                items(ecgData.size) { lead ->
                    ECGChart(leadIndex = lead)
                }
            }
        }
    }

    @Composable
    fun ECGChart(leadIndex: Int) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(150.dp).padding(8.dp),
            factory = { context ->
                LineChart(context).apply {
                    legend.isEnabled = false
                    description.isEnabled = false
                    setTouchEnabled(false)
                    setScaleEnabled(false)
                    xAxis.position = XAxis.XAxisPosition.BOTTOM
                    axisRight.isEnabled = false
                }
            },
            update = { chart ->
                val dataSet = LineDataSet(ecgData[leadIndex], "Lead ${leadIndex + 1}").apply {
                    color = android.graphics.Color.RED
                    setDrawCircles(false)
                    lineWidth = 2f
                }
                chart.data = LineData(dataSet)
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        )
    }

    private fun startScan() {
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device.name?.contains("Feather") == true) {
                    bluetoothGatt = device.connectGatt(this@MainActivity, false, gattCallback)
                    scanner.stopScan(this)
                }
            }
        }
        scanner.startScan(scanCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(28)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(serviceUUID) ?: return
            val characteristic = service.getCharacteristic(characteristicUUID) ?: return

            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            runOnUiThread { handleEcgNotification(data) }
        }
    }

    private fun handleEcgNotification(packet: ByteArray) {
        if (packet.size == 25) {
            processFullPacket(packet)
        } else if (packet.size == 13) {
            combineHalfPacket(packet)
        }
    }

    private fun combineHalfPacket(packet: ByteArray) {
        val sampleIdx = packet[0].toInt()
        if (pendingECGSamples.containsKey(sampleIdx)) {
            val firstHalf = pendingECGSamples.remove(sampleIdx)!!
            val fullPacket = firstHalf + packet.copyOfRange(1, packet.size)
            processFullPacket(fullPacket)
        } else {
            pendingECGSamples[sampleIdx] = packet.clone()
        }
    }

    private fun processFullPacket(packet: ByteArray) {
        val xVal = globalIndex++

        for (lead in 0 until 12) {
            val high = packet[1 + lead * 2].toInt() and 0xFF
            val low = packet[2 + lead * 2].toInt() and 0xFF
            val ecgValue = ((high shl 8) or low).toShort().toInt().toFloat()

            if (ecgData[lead].size > 200) {
                ecgData[lead].removeAt(0)
            }
            ecgData[lead].add(Entry(xVal, ecgValue))

            if (lead == 0) {
                ecgBuffer.add(ecgValue.toDouble())
                if (ecgBuffer.size > 1500) ecgBuffer.removeFirst()
            }
        }
    }

    private fun startBpmUpdater() {
        val handler = Handler(Looper.getMainLooper())
        val updateTask = object : Runnable {
            override fun run() {
                computeBpmFromBuffer()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateTask)
    }


    //calculate BPM
    private fun computeBpmFromBuffer() {
        val rPeaks = detectRPeaks(ecgBuffer.toDoubleArray(), sampleRate)

        if (rPeaks.size >= 2) {
            val rrIntervals = rPeaks.windowed(2, 1) { it[1] - it[0] }
                .map { it / sampleRate }
                .filter { it in 0.4..1.5 } // Only consider reasonable RR intervals

            lastValidRR = if (rrIntervals.isNotEmpty()) rrIntervals.average() else lastValidRR
        }

        currentBpm = 60.0 / (lastValidRR ?: return)
    }


    //Rpeaks
    private fun detectRPeaks(ecgSignal: DoubleArray, fs: Double): List<Int> {
        // Compute differences between consecutive points
        val diffSignal = (0 until ecgSignal.size - 1).map { i -> ecgSignal[i + 1] - ecgSignal[i] }

        // Square the differences
        val squaredSignal = diffSignal.map { it * it }

        // Moving window integration
        val integratedSignal = squaredSignal.windowed(150, 1) { it.average() }

        // Define threshold
        val threshold = integratedSignal.average() * 1.2

        // Find peaks that exceed threshold
        return integratedSignal.indices.filter {
            it > 0 && it < integratedSignal.size - 1 &&
                    integratedSignal[it] > threshold &&
                    integratedSignal[it] > integratedSignal[it - 1] &&
                    integratedSignal[it] > integratedSignal[it + 1]
        }
    }


    private fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun Double.format(digits: Int) = String.format("%.${digits}f", this)
}
