package ro.awebdesign.brickpilot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.opencv.android.OpenCVLoader
import ro.awebdesign.brickpilot.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), HubController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var hub: HubController
    private lateinit var analyzer: FloorAnalyzer
    private lateinit var lineDetector: LineDetector
    private val avoider = Avoider()
    private val lineFollower = LineFollower()
    private val lineSteer = LineSteerController()
    private val objectFollower = ObjectFollower()

    enum class DriveMode { AVOID, LINE, FOLLOW }

    @Volatile private var driveMode = DriveMode.AVOID
    @Volatile private var vehicleType = Drivetrain.VehicleType.DIFFERENTIAL
    // Tipul 2 pornește doar după ce direcția e calibrată
    @Volatile private var steeringRange: SteeringRange? = null
    @Volatile private var steerGain = Drivetrain.DEFAULT_STEER_GAIN
    private var steerText = ""
    private val uiHandler = Handler(Looper.getMainLooper())
    private val calibrator = SteeringCalibrator(CalibrationOutput())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val autonomous = AtomicBoolean(false)
    @Volatile private var invertA = true
    @Volatile private var invertB = false
    @Volatile private var lastFree = FloatArray(3)
    @Volatile private var lastRef = FloatArray(3)
    @Volatile private var lastLinePos = Float.NaN
    @Volatile private var lastTargetFound = false
    private var cameraStarted = false
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var lastStatusMs = 0L

    private var hubText = "Hub: disconnected"
    // Porturile pe care hubul raportează un dispozitiv conectat
    private val attachedPorts = sortedSetOf<Int>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (hub.isBluetoothEnabled) showPairingDialog()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fără asta Android oprește ecranul și camera
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Bibliotecile native OpenCV, înainte de primul cadru
        if (!OpenCVLoader.initLocal()) Log.e(TAG, "OpenCV nu s-a încărcat")
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        hub = HubController(this, this)
        analyzer = FloorAnalyzer(::onFrame)
        lineDetector = LineDetector(::onLineFrame)

        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)

        setupControls()
        applyOrientation(resources.configuration)
        updateButtons()
        renderStatus()
        requestPermissionsIfNeeded()
    }

    // Rotirea nu recreează activitatea (configChanges), deci conexiunea cu hubul rămâne;
    // doar rearanjăm controalele și spunem camerei noua orientare
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
        updateRotation()
        hideSystemBars()
    }

    private fun applyOrientation(config: Configuration) {
        val portrait = config.orientation == Configuration.ORIENTATION_PORTRAIT

        // Pe verticală statusul trece sub butoanele de sus, pe toată lățimea
        binding.status.layoutParams = (binding.status.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (portrait) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            marginStart = dp(if (portrait) 8 else 186)
            marginEnd = dp(if (portrait) 8 else 186)
            topMargin = dp(if (portrait) 124 else 12)
        }

        // STOP: pe orizontală toată coloana din dreapta, pe verticală lângă START, jos
        binding.btnStop.layoutParams = (binding.btnStop.layoutParams as FrameLayout.LayoutParams).apply {
            height = if (portrait) dp(BOTTOM_BUTTON_PORTRAIT) else FrameLayout.LayoutParams.MATCH_PARENT
            gravity = if (portrait) Gravity.BOTTOM or Gravity.END else Gravity.END
            topMargin = if (portrait) 0 else dp(112)
        }
        binding.btnStart.layoutParams = (binding.btnStart.layoutParams as FrameLayout.LayoutParams).apply {
            height = dp(if (portrait) BOTTOM_BUTTON_PORTRAIT else 120)
        }

        // Sliderele: un rând pe orizontală, câte unul pe rând pe verticală
        binding.bottomBar.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        binding.bottomBar.gravity = if (portrait) Gravity.START else Gravity.CENTER_VERTICAL
        for (group in listOf(binding.thresholdGroup, binding.speedGroup, binding.steerGroup)) {
            group.layoutParams = LinearLayout.LayoutParams(
                if (portrait) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                if (portrait) 0f else 1f
            ).apply { marginStart = if (portrait || group === binding.thresholdGroup) 0 else dp(24) }
        }

        // Alegerea mașinii: imaginile una lângă alta sau una sub alta
        binding.vehicleCards.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        listOf(binding.cardType1, binding.cardType2).forEachIndexed { i, card ->
            card.layoutParams = LinearLayout.LayoutParams(
                if (portrait) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                if (portrait) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            ).apply {
                val gap = dp(6)
                if (portrait) {
                    if (i == 0) bottomMargin = gap else topMargin = gap
                } else {
                    if (i == 0) marginEnd = gap else marginStart = gap
                }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onResume() {
        super.onResume()
        updateRotation()
        if (!cameraStarted && hasPermission(Manifest.permission.CAMERA)) startCamera()
    }

    override fun onPause() {
        super.onPause()
        stopAutonomous()
    }

    override fun onDestroy() {
        super.onDestroy()
        autonomous.set(false)
        uiHandler.removeCallbacksAndMessages(null)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        hub.close()
        cameraExecutor.shutdown()
    }

    // ---------------- UI ----------------

    private fun setupControls() {
        val thr = prefs.getInt(KEY_THRESHOLD, FloorAnalyzer.DEFAULT_THRESHOLD.toInt())
        analyzer.threshold = thr.toFloat()
        binding.threshold.progress = thr
        binding.thresholdLabel.text = getString(R.string.threshold_label, thr)
        binding.threshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                analyzer.threshold = value.toFloat()
                binding.thresholdLabel.text = getString(R.string.threshold_label, value)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt(KEY_THRESHOLD, sb.progress).apply()
            }
        })

        val speed = prefs.getInt(KEY_SPEED, 100)
        avoider.speedFactor = speed / 100f
        lineFollower.speedFactor = speed / 100f
        lineSteer.speedFactor = speed / 100f
        objectFollower.speedFactor = speed / 100f
        binding.speed.progress = speed
        binding.speedLabel.text = getString(R.string.speed_label, speed)
        binding.speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                avoider.speedFactor = value / 100f
                lineFollower.speedFactor = value / 100f
                lineSteer.speedFactor = value / 100f
                objectFollower.speedFactor = value / 100f
                binding.speedLabel.text = getString(R.string.speed_label, value)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt(KEY_SPEED, sb.progress).apply()
            }
        })

        val steer = prefs.getInt(KEY_STEER_GAIN, (Drivetrain.DEFAULT_STEER_GAIN * 100).roundToInt())
        steerGain = steer / 100f
        lineSteer.steerScale = steerGain / Drivetrain.DEFAULT_STEER_GAIN
        binding.steer.progress = steer
        binding.steerLabel.text = getString(R.string.steer_label, steer)
        binding.steer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                steerGain = value / 100f
                lineSteer.steerScale = steerGain / Drivetrain.DEFAULT_STEER_GAIN
                binding.steerLabel.text = getString(R.string.steer_label, value)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt(KEY_STEER_GAIN, sb.progress).apply()
            }
        })

        // Ce a învățat la urmărirea liniei rămâne între porniri; o apăsare lungă pe „Steer” o șterge
        lineSteer.learnedGain = prefs.getFloat(KEY_LEARN_GAIN, 1f)
        lineSteer.learnedSpeed = prefs.getFloat(KEY_LEARN_SPEED, 1f)
        binding.steerLabel.setOnLongClickListener {
            lineSteer.learnedGain = 1f
            lineSteer.learnedSpeed = 1f
            saveLearned()
            renderStatus()
            Toast.makeText(this, R.string.learned_reset, Toast.LENGTH_SHORT).show()
            true
        }

        // Fiecare tip de mașină își ține propriile inversări
        binding.chkInvA.setOnCheckedChangeListener { _, on ->
            invertA = on
            prefs.edit().putBoolean(invertKeyA(), on).apply()
        }
        binding.chkInvB.setOnCheckedChangeListener { _, on ->
            invertB = on
            prefs.edit().putBoolean(invertKeyB(), on).apply()
        }
        vehicleType = Drivetrain.VehicleType.entries.getOrElse(prefs.getInt(KEY_VEHICLE, 0)) {
            Drivetrain.VehicleType.DIFFERENTIAL
        }
        applyVehicleType()
        // Alegerea se cere doar la prima pornire; apoi se schimbă din butonul mic din colț
        if (!prefs.contains(KEY_VEHICLE)) binding.vehiclePicker.visibility = View.VISIBLE
        binding.cardType1.setOnClickListener { selectVehicle(Drivetrain.VehicleType.DIFFERENTIAL) }
        binding.cardType2.setOnClickListener { selectVehicle(Drivetrain.VehicleType.STEERING) }
        binding.btnVehicle.setOnClickListener {
            // Mașina stă pe loc cât timp se alege tipul; motoarele ar primi altfel comenzi pentru alt montaj
            if (hub.isReady) stopAutonomous()
            binding.vehiclePicker.visibility = View.VISIBLE
        }

        binding.btnConnect.setOnClickListener {
            if (hub.isReady) {
                autonomous.set(false)
                hub.disconnect()
            } else {
                onConnectClicked()
            }
        }
        binding.btnStart.setOnClickListener { startAutonomous() }
        binding.btnStop.setOnClickListener { stopAutonomous() }

        // Cât merge singur, orice atingere pe imagine îl oprește, la apăsare, nu la ridicarea degetului
        binding.overlay.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && autonomous.get()) {
                stopAutonomous()
                v.performClick()
                true
            } else {
                false
            }
        }
        // Oprit, în modul urmărire: apăsare lungă pe imagine = alege din nou ținta
        binding.overlay.setOnLongClickListener {
            if (autonomous.get() || driveMode != DriveMode.FOLLOW) return@setOnLongClickListener false
            objectFollower.forgetTarget()
            Toast.makeText(this, R.string.target_reset, Toast.LENGTH_SHORT).show()
            true
        }

        driveMode = DriveMode.entries.getOrElse(prefs.getInt(KEY_DRIVE_MODE, 0)) { DriveMode.AVOID }
        updateModeButton()
        binding.btnMode.setOnClickListener {
            // Schimbarea modului în mers ar putea da o comandă bruscă; oprim întâi
            if (autonomous.get()) stopAutonomous()
            driveMode = DriveMode.entries[(driveMode.ordinal + 1) % DriveMode.entries.size]
            prefs.edit().putInt(KEY_DRIVE_MODE, driveMode.ordinal).apply()
            updateModeButton()
            renderStatus()
        }
    }

    private fun saveLearned() {
        prefs.edit()
            .putFloat(KEY_LEARN_GAIN, lineSteer.learnedGain)
            .putFloat(KEY_LEARN_SPEED, lineSteer.learnedSpeed)
            .apply()
    }

    private fun selectVehicle(type: Drivetrain.VehicleType) {
        vehicleType = type
        prefs.edit().putInt(KEY_VEHICLE, type.ordinal).apply()
        binding.vehiclePicker.visibility = View.GONE
        applyVehicleType()
        // Conectat deja: direcția se calibrează acum, fără reconectare
        if (hub.isReady && type == Drivetrain.VehicleType.STEERING) startCalibration()
        updateButtons()
        renderStatus()
    }

    private fun applyVehicleType() {
        val steering = vehicleType == Drivetrain.VehicleType.STEERING
        // La tipul 1 motoarele sunt montate în oglindă: implicit A (stânga) e inversat
        invertA = prefs.getBoolean(invertKeyA(), !steering)
        invertB = prefs.getBoolean(invertKeyB(), false)
        binding.chkInvA.isChecked = invertA
        binding.chkInvB.isChecked = invertB
        binding.chkInvA.setText(if (steering) R.string.invert_a_steer else R.string.invert_a)
        binding.chkInvB.setText(if (steering) R.string.invert_b_drive else R.string.invert_b)
        binding.btnVehicle.setText(if (steering) R.string.vehicle_btn_2 else R.string.vehicle_btn_1)
        binding.steerGroup.visibility = if (steering) View.VISIBLE else View.GONE
        steeringRange = null
        steerText = ""
    }

    private fun invertKeyA() = if (vehicleType == Drivetrain.VehicleType.STEERING) KEY_INV_STEER else KEY_INV_A

    private fun invertKeyB() = if (vehicleType == Drivetrain.VehicleType.STEERING) KEY_INV_DRIVE else KEY_INV_B

    private fun updateModeButton() {
        binding.btnMode.setText(
            when (driveMode) {
                DriveMode.AVOID -> R.string.mode_obstacles
                DriveMode.LINE -> R.string.mode_line
                DriveMode.FOLLOW -> R.string.mode_follow
            }
        )
        // Pragul de podea nu e folosit la urmărirea liniei; pe ecran rămân doar reglajele care au efect
        binding.thresholdGroup.visibility = if (driveMode == DriveMode.LINE) View.GONE else View.VISIBLE
    }

    private fun updateButtons() {
        val ready = hub.isReady
        val busy = hub.state == HubController.State.SCANNING || hub.state == HubController.State.CONNECTING
        binding.btnConnect.isEnabled = !busy
        binding.btnConnect.setText(
            when {
                ready -> R.string.btn_disconnect
                busy -> R.string.btn_connecting
                else -> R.string.btn_connect
            }
        )
        val steeringReady = vehicleType == Drivetrain.VehicleType.DIFFERENTIAL || steeringRange != null
        binding.btnStart.isEnabled = ready && steeringReady && !autonomous.get()
        binding.btnStop.isEnabled = ready
    }

    private fun renderStatus() {
        val f = lastFree
        val ports = (if (attachedPorts.isEmpty()) "" else " | motors " + attachedPorts.joinToString(" ") { HubController.portName(it) }) +
            (if (steerText.isEmpty()) "" else " | $steerText")
        if (driveMode == DriveMode.LINE) {
            val steering = vehicleType == Drivetrain.VehicleType.STEERING
            val auto = if (steering) "${lineSteer.mode}" else "${lineFollower.mode}"
            // La tipul 2 arătăm și ce a învățat: cât din viraj și cât din viteză folosește
            val learned = if (steering) {
                String.format(Locale.US, " | learned steer %.0f%% speed %.0f%%", lineSteer.learnedGain * 100, lineSteer.learnedSpeed * 100)
            } else {
                ""
            }
            val mode = (if (autonomous.get()) "AUTO $auto" else "manual") + learned
            val pos = lastLinePos
            val line = if (pos.isNaN()) "no line" else String.format(Locale.US, "line at %+.2f", pos)
            binding.status.text = String.format(Locale.US, "%s%s\n%s | %s", hubText, ports, line, mode)
            return
        }
        if (driveMode == DriveMode.FOLLOW) {
            val mode = if (autonomous.get()) "AUTO ${objectFollower.mode}" else "manual"
            val target = if (lastTargetFound) {
                String.format(
                    Locale.US, "%s at %+.2f, distance %.2f",
                    if (objectFollower.locked) "locked target" else "nearest object",
                    objectFollower.bearing, objectFollower.distance
                )
            } else {
                "no target"
            }
            binding.status.text = String.format(Locale.US, "%s%s\n%s | %s", hubText, ports, target, mode)
            return
        }
        val mode = if (autonomous.get()) "AUTO ${avoider.mode}" else "manual"
        val ref = lastRef
        binding.status.text = String.format(
            Locale.US, "%s%s\nfree L %.2f  C %.2f  R %.2f | %s\nfloor Y %.0f U %.0f V %.0f",
            hubText, ports, f[0], f[1], f[2], mode, ref[0], ref[1], ref[2]
        )
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------- Mod autonom ----------------

    private fun startAutonomous() {
        if (!hub.isReady) return
        if (vehicleType == Drivetrain.VehicleType.STEERING && steeringRange == null) return
        avoider.reset()
        lineFollower.reset()
        lineSteer.reset()
        lineDetector.reset()
        objectFollower.reset()
        hub.arm()
        autonomous.set(true)
        hub.setLed(HubController.LED_BLUE)
        updateButtons()
        renderStatus()
    }

    private fun stopAutonomous() {
        // Mai întâi oprim bucla, apoi frâna golește coada BLE
        autonomous.set(false)
        hub.emergencyBrake()
        // STOP oprește și calibrarea; se poate relua din dialogul care apare
        calibrator.cancel()
        saveLearned()
        if (hub.isReady) hub.setLed(HubController.LED_GREEN)
        updateButtons()
        renderStatus()
    }

    /** Rulează pe thread-ul de analiză, la fiecare cadru. */
    private fun onFrame(r: FloorResult) {
        binding.overlay.update(r)
        lastFree = r.free
        lastRef = floatArrayOf(r.refY, r.refU, r.refV)

        // Ținta se calculează și în manual, ca statusul să arate ce ar urmări
        val following = driveMode == DriveMode.FOLLOW
        val now = SystemClock.uptimeMillis()
        val followDrive = if (following) objectFollower.decide(r, now) else null
        if (following) lastTargetFound = objectFollower.mode != ObjectFollower.Mode.NO_TARGET
        binding.overlay.target = if (following) objectFollower.tracked else null

        if (autonomous.get()) {
            if (followDrive != null && vehicleType == Drivetrain.VehicleType.STEERING) {
                // Tipul 2 are regula lui de urmărire: roțile spre țintă, fără „rotire pe loc” inversată
                sendSteer(objectFollower.steering(followDrive, steerGain / Drivetrain.DEFAULT_STEER_GAIN))
            } else {
                sendDrive(followDrive ?: avoider.decide(r.free, now, r.frontDistance))
            }
        }

        throttledStatus()
    }

    /** Ca onFrame, dar pentru modul linie. */
    private fun onLineFrame(r: LineResult) {
        binding.overlay.updateLine(r)
        // Punctul țintit, ca să se vadă unde vrea să ajungă mașina
        val p = if (vehicleType == Drivetrain.VehicleType.STEERING) lineSteer.pursuit else lineFollower.pursuit
        binding.overlay.aim = if (autonomous.get() && !p.aimX.isNaN()) floatArrayOf(p.aimX, p.aimY) else null
        lastLinePos = LineFollower.position(r)

        if (autonomous.get()) {
            val now = SystemClock.uptimeMillis()
            if (vehicleType == Drivetrain.VehicleType.STEERING) {
                sendSteer(lineSteer.decide(r, now))
            } else {
                sendDrive(lineFollower.decide(r, now))
            }
        }

        throttledStatus()
    }

    /** Tipul 2, modul linie: unghiul și viteza vin direct din LineSteerController. */
    private fun sendSteer(s: Drivetrain.SteerDrive) {
        val range = steeringRange ?: return
        val p = Drivetrain.steerPorts(s, range, invertA, invertB)
        hub.setSteerAndDrive(steerPosition = p.steerPosition, speedB = p.speedB)
    }

    private fun sendDrive(d: Avoider.Drive) {
        if (vehicleType == Drivetrain.VehicleType.STEERING) {
            val range = steeringRange ?: return
            val p = Drivetrain.toSteerPorts(d, range, invertA, invertB, steerGain)
            hub.setSteerAndDrive(steerPosition = p.steerPosition, speedB = p.speedB)
        } else {
            val p = Drivetrain.toPorts(d, invertA, invertB)
            hub.setMotors(speedA = p.a, speedB = p.b)
        }
    }

    private fun throttledStatus() {
        val now = SystemClock.uptimeMillis()
        if (now - lastStatusMs > STATUS_INTERVAL_MS) {
            lastStatusMs = now
            runOnUiThread { renderStatus() }
        }
    }

    // ---------------- Hub ----------------

    private fun onConnectClicked() {
        if (!hasBluetoothPermissions()) {
            requestPermissionsIfNeeded()
            return
        }
        if (!hub.isBluetoothEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        showPairingDialog()
    }

    private fun showPairingDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pairing_title)
            .setMessage(R.string.pairing_msg)
            .setPositiveButton(R.string.pairing_go) { _, _ -> hub.connect() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onStateChanged(state: HubController.State, message: String) {
        hubText = "Hub: $message"
        Log.i(TAG, "Hub $state, car type $vehicleType")
        if (state != HubController.State.READY) {
            autonomous.set(false)
            stopCalibration()
            if (state == HubController.State.DISCONNECTED) {
                attachedPorts.clear()
            }
        } else if (vehicleType == Drivetrain.VehicleType.STEERING) {
            startCalibration()
        }
        updateButtons()
        renderStatus()
    }

    // ---------------- Calibrarea direcției (tipul 2) ----------------

    private fun startCalibration() {
        steeringRange = null
        steerText = "steering: calibrating..."
        Log.i(TAG, "Steering calibration started")
        hub.subscribeFine(HubController.PORT_A)
        calibrator.start(SystemClock.uptimeMillis())
        uiHandler.removeCallbacks(calibrationTick)
        uiHandler.postDelayed(calibrationTick, CALIBRATION_TICK_MS)
        updateButtons()
        renderStatus()
    }

    /** La deconectare: nu mai avem ce calibra și nici calibrare valabilă. */
    private fun stopCalibration() {
        uiHandler.removeCallbacks(calibrationTick)
        steeringRange = null
        steerText = ""
    }

    private val calibrationTick = object : Runnable {
        override fun run() {
            calibrator.tick(SystemClock.uptimeMillis())
            if (calibrator.isRunning) uiHandler.postDelayed(this, CALIBRATION_TICK_MS)
        }
    }

    private inner class CalibrationOutput : SteeringCalibrator.Output {
        override fun runSteer(speed: Int, maxPower: Int) = hub.runLimited(HubController.PORT_A, speed, maxPower)
        override fun brakeSteer() = hub.brakePort(HubController.PORT_A)
        override fun requestSteerPosition() = hub.requestPosition(HubController.PORT_A)
        override fun onEndFound(position: Int) { Log.i(TAG, "Steering end at $position°") }
        override fun centerSteer(position: Int) = hub.moveTo(HubController.PORT_A, position)

        override fun onCalibrated(range: SteeringRange) {
            steeringRange = range
            steerText = "steering ±${range.half}°"
            Log.i(TAG, "Steering calibrated: center ${range.center}, ±${range.half}°")
            updateButtons()
            renderStatus()
        }

        override fun onFailed(message: String) {
            Log.w(TAG, "Steering calibration failed: $message")
            uiHandler.removeCallbacks(calibrationTick)
            steerText = "steering not calibrated"
            updateButtons()
            renderStatus()
            if (!hub.isReady || isFinishing) return
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.calib_failed_title)
                .setMessage(message)
                .setPositiveButton(R.string.calib_retry) { _, _ -> if (hub.isReady) startCalibration() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onPortAttached(port: Int, typeId: Int, attached: Boolean) {
        if (port > HubController.PORT_D) return
        if (attached) attachedPorts.add(port) else attachedPorts.remove(port)
        renderStatus()
    }

    // Doar calibrarea direcției folosește encoderul, iar acolo doar motorul A
    override fun onEncoder(port: Int, degrees: Int) {
        if (port == HubController.PORT_A && calibrator.isRunning) calibrator.onPosition(degrees, SystemClock.uptimeMillis())
    }

    // ---------------- Cameră ----------------

    // Dacă aplicația pornește cu ecranul blocat în portret, analiza ar primi cadre rotite greșit
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = updateRotation()
    }

    private fun updateRotation() {
        val rotation = binding.root.display?.rotation ?: return
        previewUseCase?.targetRotation = rotation
        analysisUseCase?.targetRotation = rotation
    }

    private fun startCamera() {
        cameraStarted = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            // Același raport 4:3 la preview și analiză, ca grila să se suprapună corect
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .build()
            binding.preview.scaleType = PreviewView.ScaleType.FIT_CENTER
            preview.setSurfaceProvider(binding.preview.surfaceProvider)

            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            fixFrameRate(analysisBuilder)
            val analysis = analysisBuilder.build()
            // Fiecare cadru merge la viziunea modului curent
            analysis.setAnalyzer(cameraExecutor) { image ->
                val ageNs = SystemClock.elapsedRealtimeNanos() - image.imageInfo.timestamp
                val start = SystemClock.uptimeMillis()
                if (driveMode == DriveMode.LINE) lineDetector.analyze(image) else analyzer.analyze(image)
                frameStats(ageNs / 1_000_000, SystemClock.uptimeMillis() - start)
            }
            previewUseCase = preview
            analysisUseCase = analysis
            updateRotation()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Nu pot porni camera", e)
                cameraStarted = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // În lumină slabă camera coboară singură la 15 cadre/s sau mai puțin, adică o reacție de două ori mai lentă
    @OptIn(ExperimentalCamera2Interop::class)
    private fun fixFrameRate(builder: ImageAnalysis.Builder) {
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
    }

    // Statistici în logcat: cadre pe secundă, vechimea cadrului la analiză și durata analizei
    private var statFrames = 0
    private var statAgeMs = 0L
    private var statWorkMs = 0L
    private var statStartMs = 0L

    private fun frameStats(ageMs: Long, workMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (statStartMs == 0L) statStartMs = now
        statFrames++
        statAgeMs += ageMs
        statWorkMs += workMs
        if (now - statStartMs >= 3000) {
            Log.d(
                TAG, "Cameră: %.1f cadre/s, cadru vechi de %d ms, analiză %d ms".format(
                    Locale.US, statFrames * 1000f / (now - statStartMs), statAgeMs / statFrames, statWorkMs / statFrames
                )
            )
            statFrames = 0
            statAgeMs = 0
            statWorkMs = 0
            statStartMs = now
        }
    }

    // ---------------- Permisiuni ----------------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermissions() =
        requiredPermissions().filter { it != Manifest.permission.CAMERA }.all(::hasPermission)

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions().filterNot(::hasPermission)
        if (missing.isEmpty()) onPermissionsResult() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionsResult() {
        if (hasPermission(Manifest.permission.CAMERA)) {
            if (!cameraStarted) startCamera()
        } else {
            showPermissionDialog(R.string.perm_camera_msg)
            return
        }
        if (!hasBluetoothPermissions()) {
            hubText = getString(R.string.perm_bt_msg)
            renderStatus()
            showPermissionDialog(R.string.perm_bt_msg)
        }
    }

    private fun showPermissionDialog(messageRes: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_title)
            .setMessage(messageRes)
            .setCancelable(false)
            .setPositiveButton(R.string.perm_retry) { _, _ ->
                permissionLauncher.launch(requiredPermissions().filterNot(::hasPermission).toTypedArray())
            }
            .setNeutralButton(R.string.perm_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton(R.string.perm_exit) { _, _ ->
                if (!hasPermission(Manifest.permission.CAMERA)) finish()
            }
            .show()
    }

    companion object {
        private const val TAG = "BrickPilot"
        private const val PREFS = "brickpilot"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_SPEED = "speed"
        private const val KEY_DRIVE_MODE = "drive_mode"
        // Chei noi: setările vechi presupuneau A = dreapta și nu mai sunt valabile
        private const val KEY_INV_A = "inv_a_left"
        private const val KEY_INV_B = "inv_b_right"
        private const val KEY_INV_STEER = "inv_a_steer"
        private const val KEY_INV_DRIVE = "inv_b_drive"
        private const val KEY_VEHICLE = "vehicle_type"
        private const val KEY_STEER_GAIN = "steer_gain"
        private const val KEY_LEARN_GAIN = "learn_gain_v2"
        private const val KEY_LEARN_SPEED = "learn_speed_v2"
        private const val STATUS_INTERVAL_MS = 150L
        private const val CALIBRATION_TICK_MS = 50L
        // Pe verticală START și STOP sunt mai joase, ca să nu acopere chenarul galben al podelei
        private const val BOTTOM_BUTTON_PORTRAIT = 96
    }
}
