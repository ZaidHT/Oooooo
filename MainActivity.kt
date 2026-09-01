package com.example.bikeradar

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null

    // Standard BLE Radar UUIDs
    private val RADAR_SERVICE_UUID = UUID.fromString("0000a026-0000-1000-8000-00805f9b34fb")
    private val RADAR_DATA_CHAR_UUID = UUID.fromString("0000a027-0000-1000-8000-00805f9b34fb")

    // UI States
    private val connectionStatus = mutableStateOf("Disconnected")
    private val vehicleApproaching = mutableStateOf(false)
    private val targetDistance = mutableStateOf(0) // meters

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        requestAppPermissions()

        setContent {
            RadarUi(
                status = connectionStatus.value,
                isVehicle = vehicleApproaching.value,
                distance = targetDistance.value,
                onScanClick = { startBleScan() }
            )
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!bluetoothAdapter.isEnabled) {
            connectionStatus.value = "Bluetooth is OFF"
            return
        }

        connectionStatus.value = "Scanning..."
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    val deviceName = device.name ?: ""
                    if (deviceName.contains("SEEMEE", ignoreCase = true) || 
                        deviceName.contains("Magicshine", ignoreCase = true) || 
                        deviceName.contains("R300", ignoreCase = true)) {
                        
                        scanner.stopScan(this)
                        connectionStatus.value = "Connecting..."
                        connectToDevice(device)
                    }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectionStatus.value = "Connected. Discovering..."
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectionStatus.value = "Disconnected"
                    vehicleApproaching.value = false
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectionStatus.value = "Radar Active"
                    val service = gatt?.getService(RADAR_SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(RADAR_DATA_CHAR_UUID)

                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                parseRadarData(value)
            }
        })
    }

    private fun parseRadarData(data: ByteArray) {
        if (data.isNotEmpty()) {
            val targetCount = data[0].toInt() and 0xFF
            if (targetCount > 0 && data.size >= 3) {
                val distance = data[1].toInt() and 0xFF
                val threatLevel = data[2].toInt() and 0xFF

                vehicleApproaching.value = threatLevel > 0
                targetDistance.value = distance
            } else {
                vehicleApproaching.value = false
                targetDistance.value = 0
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        @SuppressLint("MissingPermission")
        bluetoothGatt?.close()
    }
}

@Composable
fun RadarUi(status: String, isVehicle: Boolean, distance: Int, onScanClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (isVehicle) Color(0xFF8B0000) else Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Magicshine Bike Radar",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Box(
                modifier = Modifier
                    .size(240.dp)
                    .background(
                        color = if (isVehicle) Color.Red else Color(0xFF1E1E1E),
                        shape = RoundedCornerShape(120.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isVehicle) {
                        Text(
                            text = "VEHICLE BEHIND!",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "$distance m",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Yellow
                        )
                    } else {
                        Text(
                            text = "CLEAR",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Green
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Status: $status",
                    fontSize = 14.sp,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onScanClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                ) {
                    Text(text = "Connect Radar", color = Color.Black)
                }
            }
        }
    }
}
