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
import android.widget.Toast

import java.util.*
import kotlin.collections.windowed

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
            val filteredSignal = applyBandpassFilter(ecgBuffer.toDoubleArray(), sampleRate)
            if(detectAFib(filteredSignal, fs = sampleRate)){
                runOnUiThread {
                    Toast.makeText(this, "Potential AFib detected", Toast.LENGTH_LONG).show()
                }
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



    //Buttersworth filter found online
    //Rpeaks
    private fun applyBandpassFilter(ecgSignal: DoubleArray, fs: Double): DoubleArray {
        val b = doubleArrayOf(0.00041655, 0.0, -0.0016662, 0.0, 0.0024993, 0.0, -0.0016662, 0.0, 0.00041655)
        val a = doubleArrayOf(1.0, -7.0913, 21.9855, -39.7767, 45.4457, -32.1631, 13.5237, -2.9823, 0.2580)

        val filteredSignal = DoubleArray(ecgSignal.size)

        for (i in 8 until ecgSignal.size) {
            filteredSignal[i] = b[0] * ecgSignal[i] +
                    b[1] * ecgSignal[i - 1] +
                    b[2] * ecgSignal[i - 2] +
                    b[3] * ecgSignal[i - 3] +
                    b[4] * ecgSignal[i - 4] +
                    b[5] * ecgSignal[i - 5] +
                    b[6] * ecgSignal[i - 6] +
                    b[7] * ecgSignal[i - 7] +
                    b[8] * ecgSignal[i - 8] -
                    a[1] * filteredSignal[i - 1] -
                    a[2] * filteredSignal[i - 2] -
                    a[3] * filteredSignal[i - 3] -
                    a[4] * filteredSignal[i - 4] -
                    a[5] * filteredSignal[i - 5] -
                    a[6] * filteredSignal[i - 6] -
                    a[7] * filteredSignal[i - 7] -
                    a[8] * filteredSignal[i - 8]
        }

        return filteredSignal
    }



    private fun detectRPeaks(ecgSignal: DoubleArray, fs: Double): List<Int> {
        val filteredSignal = applyBandpassFilter(ecgSignal, fs)

        val diffSignal = (0 until filteredSignal.size - 1).map { i -> filteredSignal[i + 1] - filteredSignal[i] }
        val squaredSignal = diffSignal.map { it * it }
        val integratedSignal = squaredSignal.windowed(150, 1) { it.average() }

        val threshold = integratedSignal.average() * 1.2

        return integratedSignal.indices.filter {
            it > 0 && it < integratedSignal.size - 1 &&
                    integratedSignal[it] > threshold &&
                    integratedSignal[it] > integratedSignal[it - 1] &&
                    integratedSignal[it] > integratedSignal[it + 1]
        }
    }
    private fun detectAFib(ecgSignal: DoubleArray, fs: Double): Boolean {
        val rPeaks = detectRPeaks(ecgBuffer.toDoubleArray(), sampleRate)
        val rrIntervals = rPeaks.windowed(2, 1) { it[1] - it[0] }
            .map { it / sampleRate }
            .filter { it in 0.4..1.5 }

        val filteredSignal = applyBandpassFilter(ecgSignal, fs)
        if (filteredSignal.size < 2) return false
        //not enough data

        // Thresholds for AFib detection (need to customize based on data)
        val sdnnThreshold = 50.0 // in milliseconds
        val rmssdThreshold = 50.0 // in milliseconds

        //average RR
        val meanRR = ecgSignal.average()

        val sdnn = Math.sqrt(rrIntervals.map { (it - meanRR) * (it - meanRR) }.average())

        val rmssd = Math.sqrt(
            rrIntervals.windowed(2, 1).map {
                if (it.size >= 2) (it[1] - it[0]) * (it[1] - it[0]) else 0.0
            }.average()
        )
        return sdnn > sdnnThreshold || rmssd > rmssdThreshold
    }
    private fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun Double.format(digits: Int) = String.format("%.${digits}f", this)

}


