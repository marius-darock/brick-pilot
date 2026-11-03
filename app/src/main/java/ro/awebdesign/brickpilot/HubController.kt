package ro.awebdesign.brickpilot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

/**
 * Legătura BLE cu LEGO Technic Hub 88012 (LEGO Wireless Protocol).
 * Toate operațiile GATT rulează pe un singur thread, cu o singură scriere în zbor.
 */
@SuppressLint("MissingPermission")
class HubController(context: Context, private val listener: Listener) {

    enum class State { IDLE, SCANNING, CONNECTING, READY, DISCONNECTED, FAILED }

    interface Listener {
        fun onStateChanged(state: State, message: String)
        fun onPortAttached(port: Int, typeId: Int, attached: Boolean)
        fun onEncoder(port: Int, degrees: Int)
    }

    private class Op(val data: ByteArray, val key: Int)

    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val thread = HandlerThread("hub-ble").apply { start() }
    private val ble = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // Accesate doar pe thread-ul ble
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<Op>()
    private var inFlight = false
    private var scanning = false

    private val lock = Any()
    @Volatile var state = State.IDLE
        private set
    // Comenzile de viteză trec doar cât timp e armat; STOP dezarmează atomic
    private var armed = false
    private var motorsActive = false
    private var lastCommandMs = 0L
    // Ultima poziție trimisă motorului de direcție, ca să nu o retrimitem la fiecare cadru
    private var lastSteer: Int? = null

    val isReady get() = state == State.READY
    val isBluetoothEnabled get() = adapter?.isEnabled == true

    // ---------------- API public ----------------

    fun connect() {
        ble.post {
            if (state == State.SCANNING || state == State.CONNECTING || state == State.READY) return@post
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null || !adapter.isEnabled) {
                setState(State.FAILED, "Bluetooth unavailable or turned off")
                return@post
            }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanning = true
            setState(State.SCANNING, "Searching for the hub...")
            scanner.startScan(listOf(filter), settings, scanCallback)
            ble.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        }
    }

    /** Pornește modul în care se acceptă comenzi de viteză. */
    fun arm() = synchronized(lock) {
        armed = true
        lastCommandMs = SystemClock.uptimeMillis()
    }

    /** Viteze -100..100 pentru porturile A și B. */
    fun setMotors(speedA: Int, speedB: Int) = synchronized(lock) {
        if (!armed || state != State.READY) return@synchronized
        lastCommandMs = SystemClock.uptimeMillis()
        motorsActive = speedA != 0 || speedB != 0
        enqueue(startSpeed(PORT_A, speedA), PORT_A)
        enqueue(startSpeed(PORT_B, speedB), PORT_B)
    }

    /** Tipul 2: poziția absolută a motorului de direcție (A) și viteza tracțiunii (B). */
    fun setSteerAndDrive(steerPosition: Int, speedB: Int) = synchronized(lock) {
        if (!armed || state != State.READY) return@synchronized
        lastCommandMs = SystemClock.uptimeMillis()
        motorsActive = speedB != 0
        if (steerPosition != lastSteer) {
            lastSteer = steerPosition
            enqueue(gotoPosition(PORT_A, steerPosition, STEER_SPEED, STEER_POWER), PORT_A)
        }
        enqueue(startSpeed(PORT_B, speedB), PORT_B)
    }

    // Comenzile de calibrare merg și când modul autonom e oprit

    fun runLimited(port: Int, speed: Int, maxPower: Int) = enqueue(startSpeed(port, speed, maxPower), port)

    fun moveTo(port: Int, position: Int) {
        synchronized(lock) { lastSteer = null }
        enqueue(gotoPosition(port, position, STEER_SPEED, STEER_POWER), port)
    }

    fun brakePort(port: Int) = enqueue(brake(port), port)

    /** Encoder mai fin pentru motorul de direcție, ca o mișcare lentă să nu pară blocare. */
    fun subscribeFine(port: Int) = enqueue(subscribePosition(port, FINE_DELTA), -1)

    /** Cere poziția curentă; răspunsul vine prin onEncoder. */
    fun requestPosition(port: Int) = enqueue(portValueRequest(port), -1)

    /** Frână activă imediat: golește coada și pune frâna prima. */
    fun emergencyBrake() = synchronized(lock) {
        armed = false
        motorsActive = false
        lastSteer = null
        lastCommandMs = SystemClock.uptimeMillis()
        ble.post {
            queue.clear()
            queue.addLast(Op(brake(PORT_A), PORT_A))
            queue.addLast(Op(brake(PORT_B), PORT_B))
            pump()
        }
    }

    fun setLed(color: Int) = enqueue(led(color), PORT_LED)

    /** Frânează, apoi închide conexiunea cu hubul. */
    fun disconnect() {
        emergencyBrake()
        // Lăsăm frâna să plece înainte de deconectare
        ble.postDelayed({
            stopScan()
            val g = gatt ?: return@postDelayed
            g.disconnect()
            // Rezervă dacă nu vine callback-ul de deconectare
            ble.postDelayed({ if (gatt === g) onDisconnected(g, "Disconnected") }, 1000)
        }, 150)
    }

    fun close() {
        synchronized(lock) { armed = false }
        ble.post {
            stopScan()
            ble.removeCallbacksAndMessages(null)
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            characteristic = null
            queue.clear()
            thread.quitSafely()
        }
    }

    // ---------------- Scanare ----------------

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            setState(State.FAILED, "Hub not found. Hold the green button until it blinks white.")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            ble.post {
                if (!scanning) return@post
                stopScan()
                connectDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            ble.post {
                scanning = false
                ble.removeCallbacks(scanTimeout)
                setState(State.FAILED, "Scan failed (code $errorCode)")
            }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        ble.removeCallbacks(scanTimeout)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun connectDevice(device: BluetoothDevice) {
        setState(State.CONNECTING, "Connecting to ${device.address}")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ---------------- GATT ----------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            ble.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(MTU)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (gatt === g) onDisconnected(g, "Hub disconnected (status $status)") else g.close()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            ble.post { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            ble.post {
                val ch = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
                if (ch == null) {
                    setState(State.FAILED, "LWP service missing")
                    g.disconnect()
                    return@post
                }
                characteristic = ch
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID)
                inFlight = true
                if (cccd == null || !writeDescriptor(g, cccd)) {
                    inFlight = false
                    onReady()
                } else {
                    // Rezervă dacă onDescriptorWrite nu vine
                    ble.postDelayed({
                        if (gatt === g && characteristic != null && state != State.READY) {
                            inFlight = false
                            onReady()
                        }
                    }, 1000)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            ble.post {
                inFlight = false
                onReady()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            ble.post {
                inFlight = false
                ble.removeCallbacks(writeTimeout)
                pump()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(value)
        }

        // Folosit doar sub Android 13
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            ch.value?.let { handleNotification(it.copyOf()) }
        }
    }

    private fun onDisconnected(g: BluetoothGatt, message: String) {
        synchronized(lock) { armed = false; motorsActive = false; lastSteer = null }
        g.close()
        gatt = null
        characteristic = null
        queue.clear()
        inFlight = false
        ble.removeCallbacks(watchdog)
        ble.removeCallbacks(writeTimeout)
        setState(State.DISCONNECTED, message)
    }

    private fun onReady() {
        if (state == State.READY) return
        setState(State.READY, "Connected")
        enqueue(subscribePosition(PORT_A), -1)
        enqueue(subscribePosition(PORT_B), -1)
        enqueue(brake(PORT_A), PORT_A)
        enqueue(brake(PORT_B), PORT_B)
        setLed(LED_GREEN)
        ble.postDelayed(watchdog, WATCHDOG_TICK_MS)
    }

    private fun handleNotification(v: ByteArray) {
        if (v.size < 3) return
        when (v[2].toInt() and 0xFF) {
            MSG_PORT_VALUE_SINGLE -> if (v.size >= 8) {
                val port = v[3].toInt() and 0xFF
                val pos = (v[4].toInt() and 0xFF) or
                    ((v[5].toInt() and 0xFF) shl 8) or
                    ((v[6].toInt() and 0xFF) shl 16) or
                    ((v[7].toInt() and 0xFF) shl 24)
                main.post { listener.onEncoder(port, pos) }
            }
            MSG_HUB_ATTACHED_IO -> if (v.size >= 5) {
                val port = v[3].toInt() and 0xFF
                val event = v[4].toInt() and 0xFF
                val type = if (v.size >= 7) (v[5].toInt() and 0xFF) or ((v[6].toInt() and 0xFF) shl 8) else 0
                main.post { listener.onPortAttached(port, type, event != 0) }
            }
        }
    }

    // ---------------- Coada de scrieri ----------------

    private fun enqueue(data: ByteArray, key: Int) {
        ble.post {
            if (state != State.READY) return@post
            // O comandă nouă pentru același port o înlocuiește pe cea neexpediată
            if (key >= 0) queue.removeAll { it.key == key }
            queue.addLast(Op(data, key))
            while (queue.size > MAX_PENDING) queue.removeFirst()
            pump()
        }
    }

    private fun pump() {
        if (inFlight) return
        val g = gatt ?: return
        val ch = characteristic ?: return
        val op = queue.pollFirst() ?: return
        inFlight = true
        if (writeCharacteristic(g, ch, op.data)) {
            ble.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
        } else {
            // Stiva BLE e ocupată: reîncercăm curând
            inFlight = false
            queue.addFirst(op)
            ble.postDelayed({ pump() }, 5)
        }
    }

    // Unele telefoane nu confirmă scrierile fără răspuns; nu blocăm coada
    private val writeTimeout = Runnable {
        inFlight = false
        pump()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (state != State.READY) return
            val stale = synchronized(lock) {
                val s = motorsActive && SystemClock.uptimeMillis() - lastCommandMs > WATCHDOG_MS
                if (s) { motorsActive = false; lastSteer = null }
                s
            }
            if (stale) {
                Log.w(TAG, "Watchdog: nicio comandă de ${WATCHDOG_MS} ms, frânez")
                queue.clear()
                queue.addLast(Op(brake(PORT_A), PORT_A))
                queue.addLast(Op(brake(PORT_B), PORT_B))
                pump()
            }
            ble.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    private fun writeCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = data
                g.writeCharacteristic(ch)
            }
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = value
                g.writeDescriptor(d)
            }
        }
    }

    private fun setState(s: State, msg: String) {
        state = s
        Log.i(TAG, "Hub: $s $msg")
        main.post { listener.onStateChanged(s, msg) }
    }

    companion object {
        private const val TAG = "BrickPilot"

        val SERVICE_UUID: UUID = UUID.fromString("00001623-1212-efde-1623-785feabcd123")
        val CHAR_UUID: UUID = UUID.fromString("00001624-1212-efde-1623-785feabcd123")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val PORT_A = 0x00
        const val PORT_B = 0x01
        const val PORT_C = 0x02
        const val PORT_D = 0x03
        const val PORT_LED = 0x32

        const val LED_OFF = 0
        const val LED_BLUE = 3
        const val LED_GREEN = 6
        const val LED_RED = 9

        private const val MSG_HUB_ATTACHED_IO = 0x04
        private const val MSG_PORT_VALUE_SINGLE = 0x45

        private const val MTU = 69
        private const val MAX_PENDING = 8
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val WRITE_TIMEOUT_MS = 60L
        private const val WATCHDOG_MS = 400L
        private const val WATCHDOG_TICK_MS = 50L
        private const val MAX_POWER = 100
        private const val BRAKE = 127
        private const val HOLD = 126
        private const val FINE_DELTA = 2
        // Toată puterea: roțile apăsate pe podea fac direcția grea; cursa e oricum limitată de calibrare
        private const val STEER_SPEED = 100
        private const val STEER_POWER = 100

        fun startSpeed(port: Int, speed: Int, maxPower: Int = MAX_POWER): ByteArray = byteArrayOf(
            0x09, 0x00, 0x81.toByte(), port.toByte(), 0x11, 0x07,
            speed.coerceIn(-100, 100).toByte(), maxPower.coerceIn(0, 100).toByte(), 0x00
        )

        /** GotoAbsolutePosition: merge la poziția dată (în sistemul encoderului) și o ține. */
        fun gotoPosition(port: Int, position: Int, speed: Int, maxPower: Int): ByteArray = byteArrayOf(
            0x0E, 0x00, 0x81.toByte(), port.toByte(), 0x11, 0x0D,
            position.toByte(), (position shr 8).toByte(), (position shr 16).toByte(), (position shr 24).toByte(),
            speed.coerceIn(0, 100).toByte(), maxPower.coerceIn(0, 100).toByte(), HOLD.toByte(), 0x00
        )

        /** Port Information Request pentru valoarea curentă a portului. */
        fun portValueRequest(port: Int): ByteArray = byteArrayOf(0x05, 0x00, 0x21, port.toByte(), 0x00)

        fun brake(port: Int): ByteArray = byteArrayOf(
            0x08, 0x00, 0x81.toByte(), port.toByte(), 0x11, 0x51, 0x00, BRAKE.toByte()
        )

        fun led(color: Int): ByteArray = byteArrayOf(
            0x08, 0x00, 0x81.toByte(), PORT_LED.toByte(), 0x11, 0x51, 0x00, color.coerceIn(0, 10).toByte()
        )

        fun subscribePosition(port: Int, deltaDegrees: Int = 5): ByteArray = byteArrayOf(
            0x0A, 0x00, 0x41, port.toByte(), 0x02, deltaDegrees.toByte(), 0x00, 0x00, 0x00, 0x01
        )

        fun portName(port: Int) = when (port) {
            PORT_A -> "A"; PORT_B -> "B"; PORT_C -> "C"; PORT_D -> "D"
            else -> "0x%02X".format(port)
        }
    }
}
