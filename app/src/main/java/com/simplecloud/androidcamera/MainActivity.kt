package com.simplecloud.androidcamera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var formatBadge: TextView
    private lateinit var zoomBadge: TextView
    private lateinit var resolutionBadge: View
    private lateinit var exposurePanel: View
    private lateinit var qualityText: TextView
    private lateinit var resolutionText: TextView
    private lateinit var exposureValueText: TextView
    private lateinit var exposureAuto: TextView
    private lateinit var previewSurface: SurfaceView

    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var previewSession: android.hardware.camera2.CameraCaptureSession? = null
    private var cameraOpening = false
    private var activityResumed = false
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var surfaceReady = false
    private var previewSize: Size? = null
    private var orientationListener: OrientationEventListener? = null
    private var deviceRotation = Surface.ROTATION_0

    private var frontCamera = false

    private enum class FlashMode { ON, AUTO, OFF }
    private var flashMode = FlashMode.AUTO

    private var zoomIndex = 0
    private var exposureAutoMode = true
    private var exposureValue = 0.0f
    private var selectedResolutionTier = 0

    private val zoomLevels = listOf("1.0×", "1.5×", "2.0×")

    private var cameraInventory: List<CameraDetails> = emptyList()
    private var selectedCamera: CameraDetails? = null

    companion object {
        private const val TAG = "SimpleCam.Camera"
        private const val CAMERA_PERMISSION_REQUEST = 1001

        private const val MP_8 = 8
        private const val MP_12 = 12
        private const val MP_24 = 24
        private const val MP_48 = 48

        private val FIXED_TIERS = intArrayOf(MP_8, MP_12, MP_24, MP_48)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        formatBadge = findViewById(R.id.formatBadge)
        zoomBadge = findViewById(R.id.zoomBadge)
        resolutionBadge = findViewById(R.id.resolutionBadge)
        exposurePanel = findViewById(R.id.exposurePanel)
        qualityText = findViewById(R.id.qualityText)
        resolutionText = findViewById(R.id.resolutionText)
        exposureValueText = findViewById(R.id.exposureValueText)
        exposureAuto = findViewById(R.id.exposureAuto)
        previewSurface = findViewById(R.id.previewSurface)
        updateExposureLabel()
        updateFlashIcon()
        setupAutoRotate()
        setupExposureOutsideTouchDismiss()

        previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                startPreviewIfReady()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                surfaceReady = true
                // The configured Surface buffer size is managed when the camera
                // is opened; don't tear down a working session on every layout
                // change.
                if (cameraDevice == null && !cameraOpening) {
                    startPreviewIfReady()
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                closePreview()
            }
        })

        findViewById<View>(R.id.shutterOuter).setOnClickListener {
            Toast.makeText(this, "UI preview: capture action", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.switchCameraButton).setOnClickListener {
            frontCamera = !frontCamera
            closePreview()
            updateSelectedCamera()
            previewSurface.postDelayed({ startPreviewIfReady() }, 100)
            Toast.makeText(
                this,
                if (frontCamera) "Front camera selected" else "Rear 1× camera selected",
                Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<View>(R.id.flashButton).setOnClickListener {
            flashMode = when (flashMode) {
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
                FlashMode.OFF -> FlashMode.ON
            }
            updateFlashIcon()
        }

        zoomBadge.setOnClickListener {
            zoomIndex = (zoomIndex + 1) % zoomLevels.size
            zoomBadge.text = zoomLevels[zoomIndex]
        }

        qualityText.setOnClickListener {
            exposurePanel.visibility =
                if (exposurePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<android.widget.SeekBar>(R.id.exposureSeekBar).setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (!fromUser) return
                    exposureAutoMode = false
                    exposureValue = (progress - 10) * 0.1f
                    updateExposureLabel()
                    // The value text is intentionally updated on every slider
                    // movement so the current EV is visible while dragging.
                    exposureValueText.text =
                        if (exposureValue >= 0f) {
                            "+%.1f".format(java.util.Locale.US, exposureValue)
                        } else {
                            "%.1f".format(java.util.Locale.US, exposureValue)
                        }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            }
        )

        findViewById<View>(R.id.exposureClose).setOnClickListener {
            exposurePanel.visibility = View.GONE
        }

        exposureAuto.setOnClickListener {
            exposureAutoMode = true
            exposureValue = 0.0f
            findViewById<android.widget.SeekBar>(R.id.exposureSeekBar).progress = 10
            updateExposureLabel()
        }

        findViewById<View>(R.id.viewfinder).setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val reticle = findViewById<View>(R.id.focusReticle)
                reticle.x = event.x - reticle.width / 2f
                reticle.y = event.y - reticle.height / 2f
                reticle.alpha = 1f
                reticle.animate().alpha(0.55f).setDuration(450).start()
                view.performClick()
                true
            } else {
                false
            }
        }

        formatBadge.setOnClickListener {
            formatBadge.text = if (formatBadge.text == "HEIC") "JPEG" else "HEIC"
        }

        resolutionBadge.setOnClickListener {
            cycleResolutionTier()
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            collectCameraDetails()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            collectCameraDetails()
        } else {
            resolutionText.text = "— MP"
            Toast.makeText(
                this,
                "Camera permission is required to inspect camera capabilities.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Capability collection only.
     *
     * No CameraDevice is opened and no image is captured in this phase.
     */
    private fun updateFlashIcon() {
        val button = findViewById<android.widget.ImageButton>(R.id.flashButton)
        val drawableRes = when (flashMode) {
            FlashMode.ON -> R.drawable.ic_flash
            FlashMode.AUTO -> R.drawable.ic_flash_auto
            FlashMode.OFF -> R.drawable.ic_flash_off
        }
        button.setImageResource(drawableRes)
        button.contentDescription = when (flashMode) {
            FlashMode.ON -> "Flash on"
            FlashMode.AUTO -> "Flash auto"
            FlashMode.OFF -> "Flash off"
        }
    }

    private fun updateExposureLabel() {
        if (!::qualityText.isInitialized) return

        if (exposureAutoMode) {
            qualityText.text = "EV"
            qualityText.setTextColor(
                resources.getColor(R.color.camera_ev_auto, theme)
            )
            if (::exposureValueText.isInitialized) {
                exposureValueText.text = "AUTO"
                exposureValueText.setTextColor(
                    resources.getColor(R.color.camera_muted, theme)
                )
            }
            if (::exposureAuto.isInitialized) {
                exposureAuto.setTextColor(
                    resources.getColor(R.color.camera_ev_auto, theme)
                )
            }
        } else {
            val valueText =
                if (exposureValue >= 0f) {
                    "+%.1f".format(java.util.Locale.US, exposureValue)
                } else {
                    "%.1f".format(java.util.Locale.US, exposureValue)
                }

            qualityText.text = valueText
            qualityText.setTextColor(
                resources.getColor(R.color.camera_text, theme)
            )

            if (::exposureValueText.isInitialized) {
                exposureValueText.text = valueText
                exposureValueText.setTextColor(
                    resources.getColor(R.color.camera_text, theme)
                )
            }
            if (::exposureAuto.isInitialized) {
                exposureAuto.setTextColor(
                    resources.getColor(R.color.camera_ev_auto, theme)
                )
            }
        }
    }

    private fun setupExposureOutsideTouchDismiss() {
        window.decorView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                exposurePanel.visibility == View.VISIBLE
            ) {
                val location = IntArray(2)
                exposurePanel.getLocationOnScreen(location)

                val left = location[0].toFloat()
                val top = location[1].toFloat()
                val right = left + exposurePanel.width
                val bottom = top + exposurePanel.height

                val insidePanel =
                    event.rawX >= left &&
                    event.rawX <= right &&
                    event.rawY >= top &&
                    event.rawY <= bottom

                if (!insidePanel) {
                    exposurePanel.visibility = View.GONE
                }
            }

            // Never consume the event. This is important for SeekBar dragging
            // and for all controls inside the EV panel.
            view.onTouchEvent(event)
        }
    }

    private fun setupAutoRotate() {
        orientationListener = object : OrientationEventListener(this) {
            private var lastRotation = Surface.ROTATION_0

            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val rotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_90
                    orientation < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_270
                }

                if (rotation == lastRotation) return
                lastRotation = rotation
                deviceRotation = rotation

                // The Activity stays portrait. Only the controls rotate around
                // their own center, so their screen position does not change.
                val angle = when (rotation) {
                    Surface.ROTATION_90 -> -90f
                    Surface.ROTATION_180 -> 180f
                    Surface.ROTATION_270 -> 90f
                    else -> 0f
                }

                rotateControlViews(angle)
            }
        }

        if (orientationListener?.canDetectOrientation() == true) {
            orientationListener?.enable()
        }
    }

    private fun rotateControlViews(angle: Float) {
        /*
         * Rotate the control contents in place. The MP bubble itself is kept
         * unrotated so its rounded bounds never extend outside the preview
         * clipping region when the device rotates.
         *
         * The TextView is the visible MP content, so rotating it gives the
         * desired readable orientation without rotating the bubble container.
         */
        val controls = listOf(
            findViewById<View>(R.id.formatBadge),
            findViewById<View>(R.id.flashButton),
            findViewById<View>(R.id.zoomBadge),
            findViewById<View>(R.id.qualityText),
            findViewById<View>(R.id.galleryButton),
            findViewById<View>(R.id.shutterOuter),
            findViewById<View>(R.id.switchCameraButton),
            findViewById<View>(R.id.exposureAuto),
            findViewById<View>(R.id.exposureMinus),
            findViewById<View>(R.id.exposurePlus),
            findViewById<View>(R.id.exposureValueText)
        )

        controls.forEach { view ->
            view.animate()
                .rotation(angle)
                .setDuration(180)
                .start()
        }

        // Keep the MP bubble/background fixed. Rotate only the MP text so
        // the rounded bubble never extends outside its original bounds.
        resolutionText.animate()
            .rotation(angle)
            .setDuration(180)
            .start()

        rotateExposureControls(angle)
    }

    private fun rotateExposureControls(angle: Float) {
        if (!::exposurePanel.isInitialized) return

        listOf(
            R.id.exposureMinus,
            R.id.exposureValueText,
            R.id.exposurePlus
        ).forEach { id ->
            findViewById<View>(id)?.rotation = angle
        }
    }

    private fun collectCameraDetails() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val all = mutableListOf<CameraDetails>()

        for (cameraId in manager.cameraIdList) {
            try {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                all += inspectCamera(manager, cameraId, characteristics)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inspect cameraId=$cameraId", e)
            }
        }

        cameraInventory = all

        // Product camera policy: select the rear primary 1× camera.
        // Front camera remains the best available front-facing camera.
        updateSelectedCamera()

        Log.i(TAG, "========== SIMPLECAM CAMERA INVENTORY ==========")
        all.forEach { logCameraDetails(it) }
        Log.i(TAG, "=================================================")
    }

    private fun inspectCamera(
        manager: CameraManager,
        cameraId: String,
        characteristics: CameraCharacteristics
    ): CameraDetails {

        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            characteristics.physicalCameraIds.toList()
        } else {
            emptyList()
        }

        val isLogical =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                    ) == true

        /*
         * For a logical multi-camera, the logical device's static sensor metadata
         * can describe the default active physical camera rather than the full
         * high-resolution physical sensor. Inspect each physical camera directly.
         * Android permits getCameraCharacteristics(physicalId) for physical cameras
         * that are not independently exposed from API 29 onward.
         */
        val physicalDetails = if (isLogical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            physicalIds.mapNotNull { physicalId ->
                try {
                    inspectSingleCamera(
                        manager,
                        physicalId,
                        manager.getCameraCharacteristics(physicalId),
                        isPhysical = true
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not inspect physical camera $physicalId", e)
                    null
                }
            }
        } else {
            emptyList()
        }

        val logicalDetails = inspectSingleCamera(
            manager,
            cameraId,
            characteristics,
            isPhysical = false
        )

        /*
         * Product rule: the rear camera is the 1× primary camera, approximately
         * 5–6 mm equivalent. We cannot derive a universal "equivalent mm" directly
         * from Camera2 metadata, so for this discovery phase we use the physical
         * focal length as the strongest available signal and keep all metadata
         * for later calibration.
         *
         * The selected physical sensor becomes the source of sensor MP and
         * maximum-resolution capability for the fixed 8/12/24/48 MP policy.
         */
        val primaryPhysical = if (isLogical && facing == CameraCharacteristics.LENS_FACING_BACK) {
            selectPrimaryPhysicalOneX(physicalDetails)
        } else {
            null
        }

        val basis = primaryPhysical ?: logicalDetails

        return logicalDetails.copy(
            selectedPhysicalId = primaryPhysical?.cameraId,
            sensorWidth = basis.sensorWidth,
            sensorHeight = basis.sensorHeight,
            sensorMp = basis.sensorMp,
            normalSensorMp = basis.normalSensorMp,
            maximumSensorMp = basis.maximumSensorMp,
            ultraHighResolutionSensor = basis.ultraHighResolutionSensor,
            focalLengths = basis.focalLengths,
            normalJpegSizes = basis.normalJpegSizes,
            maximumJpegSizes = basis.maximumJpegSizes,
            defaultTierMp = calculateDefaultTier(basis.sensorMp),
            supportedTiers = calculateSupportedTiers(
                sensorMp = basis.sensorMp,
                normalJpegSizes = basis.normalJpegSizes,
                maximumJpegSizes = basis.maximumJpegSizes
            ),
            physicalDetails = physicalDetails
        )
    }

    private fun inspectSingleCamera(
        manager: CameraManager,
        cameraId: String,
        characteristics: CameraCharacteristics,
        isPhysical: Boolean
    ): CameraDetails {

        val normalSensor =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)

        val maxSensor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            characteristics.get(
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION
            )
        } else {
            null
        }

        val normalMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val maximumMap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
            )
        } else {
            null
        }

        val normalSensorMp = areaMp(normalSensor)
        val maximumSensorMp = areaMp(maxSensor)
        val normalJpegSizes = jpegSizes(normalMap)
        val maximumJpegSizes = jpegSizes(maximumMap)

        val resolvedSensorMp = resolveCapabilityMp(
            normalSensor = normalSensor,
            maximumSensor = maxSensor,
            normalJpegSizes = normalJpegSizes,
            maximumJpegSizes = maximumJpegSizes
        )

        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

        val capabilities =
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()

        val ultraHighResolutionSensor =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
                )

        return CameraDetails(
            cameraId = cameraId,
            facing = facingLabel(facing),
            isPhysical = isPhysical,
            isLogicalMultiCamera =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                        ) == true,
            physicalCameraIds =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    characteristics.physicalCameraIds.toList()
                } else {
                    emptyList()
                },
            selectedPhysicalId = null,
            physicalDetails = emptyList(),
            sensorWidth = maxOf(
                normalSensor?.width ?: 0,
                maxSensor?.width ?: 0
            ),
            sensorHeight = maxOf(
                normalSensor?.height ?: 0,
                maxSensor?.height ?: 0
            ),
            sensorMp = resolvedSensorMp,
            normalSensorMp = normalSensorMp,
            maximumSensorMp = maximumSensorMp,
            ultraHighResolutionSensor = ultraHighResolutionSensor,
            hardwareLevel =
                characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?.toString() ?: "UNKNOWN",
            sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            flashAvailable =
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            focalLengths =
                characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )?.toList() ?: emptyList(),
            normalJpegSizes = normalJpegSizes,
            maximumJpegSizes = maximumJpegSizes,
            maxDigitalZoom =
                characteristics.get(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
                ) ?: 1.0f,
            defaultTierMp = calculateDefaultTier(resolvedSensorMp),
            supportedTiers = calculateSupportedTiers(
                sensorMp = resolvedSensorMp,
                normalJpegSizes = normalJpegSizes,
                maximumJpegSizes = maximumJpegSizes
            )
        )
    }

    private fun selectPrimaryPhysicalOneX(
        physicalCameras: List<CameraDetails>
    ): CameraDetails? {
        if (physicalCameras.isEmpty()) return null

        /*
         * Primary 1× heuristic for discovery:
         * - reject obvious ultrawide candidates (<2.5 mm physical focal length)
         * - reject obvious telephoto candidates (>8 mm physical focal length)
         * - among remaining candidates, choose the one closest to 5.5 mm
         * - if focal length is unavailable, prefer the highest-resolution sensor
         *
         * This is deliberately a capability-discovery heuristic. The actual
         * capture path should verify the selected physical stream/session.
         */
        val withFocal = physicalCameras.filter { it.focalLengths.isNotEmpty() }

        if (withFocal.isNotEmpty()) {
            return withFocal.minWithOrNull(
                compareBy<CameraDetails> {
                    primaryFocalLengthPenalty(it.primaryFocalLengthMm)
                }.thenByDescending { it.sensorMp }
            )
        }

        return physicalCameras.maxByOrNull { it.sensorMp }
    }

    private fun primaryFocalLengthPenalty(mm: Float): Double {
        return when {
            mm < 2.5f -> 100.0 + abs(mm.toDouble() - 2.5)
            mm > 8.0f -> 100.0 + abs(mm.toDouble() - 8.0)
            else -> abs(mm.toDouble() - 5.5)
        }
    }

    /**
     * Fixed product tiers only.
     *
     * The sensor determines the maximum entitlement:
     * >48 -> 48
     * >24 -> 24
     * >12 -> 12
     * >8  -> 8
     *
     * The UI never exposes arbitrary vendor sizes such as 9.4, 13 or 16 MP.
     */
    private fun resolveCapabilityMp(
        normalSensor: Size?,
        maximumSensor: Size?,
        normalJpegSizes: List<Size>,
        maximumJpegSizes: List<Size>
    ): Double {
        /*
         * Do not use the largest NORMAL JPEG stream as the sensor MP.
         *
         * Smartphone main cameras commonly expose a binned normal stream
         * (for example ~12 MP from a 48/50 MP sensor). The product capability
         * must be based on the highest genuine sensor-resolution evidence.
         *
         * API 31+ maximum-resolution metadata is the preferred source when
         * present. If it is absent, the normal sensor pixel array is used.
         * JPEG sizes are only a last-resort fallback because they describe
         * processed output, not necessarily native sensor resolution.
         */
        val maximumMp = areaMp(maximumSensor)
        val normalMp = areaMp(normalSensor)

        if (maximumMp > normalMp && maximumMp > 0.0) {
            return maximumMp
        }

        if (normalMp > 0.0) {
            return normalMp
        }

        return areaMp(
            maximumJpegSizes.maxByOrNull {
                it.width.toLong() * it.height.toLong()
            } ?: normalJpegSizes.maxByOrNull {
                it.width.toLong() * it.height.toLong()
            }
        )
    }

    private fun calculateDefaultTier(sensorMp: Double): Int {
        return when {
            sensorMp > 48.0 -> MP_48
            sensorMp > 24.0 -> MP_24
            sensorMp > 12.0 -> MP_12
            sensorMp > 8.0 -> MP_8
            else -> 0
        }
    }

    /**
     * Determine which fixed tiers have a real advertised compressed output.
     *
     * A tier is considered available when a JPEG output in that tier exists
     * in either normal or maximum-resolution mode. We never upscale.
     *
     * For a tier target, the resolver prefers an advertised output whose pixel
     * count is closest to the target without exceeding it.
     */
    private fun calculateSupportedTiers(
        sensorMp: Double,
        normalJpegSizes: List<Size>,
        maximumJpegSizes: List<Size>
    ): List<Int> {

        val allSizes = (normalJpegSizes + maximumJpegSizes)
            .distinctBy { "${it.width}x${it.height}" }

        val maxEntitledTier = calculateDefaultTier(sensorMp)
        if (maxEntitledTier == 0 || allSizes.isEmpty()) {
            return emptyList()
        }

        /*
         * The UI is a product-resolution selector, not a mirror of the
         * binned/default Camera2 JPEG list. A high-resolution sensor therefore
         * retains all lower fixed tiers even when the normal stream map only
         * advertises the binned 12/8 MP outputs.
         *
         * Actual capture will later resolve each selected tier against the
         * appropriate normal or maximum-resolution stream configuration.
         */
        return FIXED_TIERS.filter { it <= maxEntitledTier }
    }

    private fun hasUsableOutputForTier(
        tierMp: Int,
        sizes: List<Size>
    ): Boolean {
        val target = tierMp * 1_000_000L

        // We allow a small vendor dimension tolerance around the fixed MP
        // tier, but never accept an output that is materially below the tier
        // and never upscale.
        val lowerBound = (target * 0.90).toLong()
        val upperBound = (target * 1.05).toLong()

        return sizes.any {
            val pixels = it.width.toLong() * it.height.toLong()
            pixels in lowerBound..upperBound
        }
    }

    /**
     * Multi-camera selection:
     *
     * For rear-facing cameras, prefer a camera that looks like the device's
     * normal 1× camera. We use focal length as an initial hardware signal,
     * while keeping the full inventory so we can refine the physical/logical
     * mapping after observing real devices.
     *
     * We intentionally do not select a telephoto or ultrawide solely because
     * it has more MP.
     */
    private fun selectPrimaryFrontCamera(
        cameras: List<CameraDetails>
    ): CameraDetails? {
        val front = cameras.filter { it.facing == "FRONT" }
        if (front.isEmpty()) return null

        // Prefer an actual standalone/physical front sensor over a logical
        // grouping whose maximum-resolution metadata may describe another mode.
        val standalone = front.filter { !it.isLogicalMultiCamera && !it.isPhysical }
        if (standalone.isNotEmpty()) return standalone.maxByOrNull { it.sensorMp }

        val physical = front.filter { it.isPhysical }
        if (physical.isNotEmpty()) return physical.maxByOrNull { it.sensorMp }

        return front.minByOrNull { it.sensorMp }
    }

    private fun selectPrimaryOneXCamera(cameras: List<CameraDetails>): CameraDetails? {
        val rear = cameras.filter { it.facing == "BACK" }
        if (rear.isEmpty()) return null

        // Prefer a logical multi-camera whose physical 1× camera was resolved.
        val logicalPrimary = rear
            .filter { it.isLogicalMultiCamera && it.selectedPhysicalId != null }
            .maxByOrNull { it.sensorMp }

        if (logicalPrimary != null) return logicalPrimary

        // Otherwise use a standalone rear camera with a plausible 1× focal length.
        val candidates = rear.filter { it.focalLengths.isNotEmpty() }

        return candidates.minWithOrNull(
            compareBy<CameraDetails> {
                primaryFocalLengthPenalty(it.primaryFocalLengthMm)
            }.thenByDescending {
                it.sensorMp
            }
        ) ?: rear.maxByOrNull { it.sensorMp }
    }

    private fun updateSelectedCamera() {
        selectedCamera =
            if (frontCamera) {
                selectPrimaryFrontCamera(cameraInventory)
            } else {
                selectPrimaryOneXCamera(cameraInventory)
            }

        val camera = selectedCamera

        if (camera == null || camera.defaultTierMp == 0) {
            selectedResolutionTier = 0
            resolutionText.text = "— MP"
            closePreview()
            return
        }

        // Default is the highest product tier approved for this camera.
        selectedResolutionTier =
            if (camera.supportedTiers.contains(camera.defaultTierMp)) {
                camera.defaultTierMp
            } else {
                camera.supportedTiers.maxOrNull() ?: camera.defaultTierMp
            }

        resolutionText.text = "$selectedResolutionTier MP"

        Log.i(
            TAG,
            "Selected ${if (frontCamera) "FRONT" else "REAR 1×"} camera=${camera.cameraId}, " +
                "sensor=${formatMp(camera.sensorMp)} MP, " +
                "defaultTier=${camera.defaultTierMp} MP, " +
                "supportedTiers=${camera.supportedTiers}, " +
                "selectedTier=${selectedResolutionTier} MP"
        )
    }

    /**
     * Resolution selector uses only the fixed product tiers. It never exposes
     * arbitrary Camera2 output sizes in the UI.
     *
     * A tier is selectable only when the capability resolver found a real
     * advertised compressed output close to that tier. Actual capture mapping
     * will use that approved output size in the capture phase.
     */
    private fun cycleResolutionTier() {
        val camera = selectedCamera ?: return
        val tiers = camera.supportedTiers.sortedDescending()

        if (tiers.isEmpty()) return

        val currentIndex = tiers.indexOf(selectedResolutionTier)
        val nextIndex =
            if (currentIndex < 0) 0
            else (currentIndex + 1) % tiers.size

        selectedResolutionTier = tiers[nextIndex]
        resolutionText.text = "$selectedResolutionTier MP"

        Log.i(
            TAG,
            "Resolution tier changed to ${selectedResolutionTier} MP; " +
                "available=${tiers}"
        )
    }


    private fun startCameraThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("SimpleCam-Camera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun startPreviewIfReady() {
        if (!activityResumed || !surfaceReady || selectedCamera == null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (cameraDevice != null || cameraOpening) return

        val surface = previewSurface.holder.surface
        if (!surface.isValid) return

        startCameraThread()

        val cameraId = selectedCamera!!.cameraId
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run {
                    Log.e(TAG, "No stream configuration map for camera $cameraId")
                    return
                }

            /*
             * SurfaceView/SurfaceHolder is a real camera output target. Pick only
             * sizes advertised for SurfaceHolder and set the holder's buffer size
             * to that exact advertised size before configuring the session.
             *
             * Previously we selected a size but never applied it to the Surface,
             * allowing the SurfaceView to request a vendor-unsupported buffer
             * size. Some devices reject that with Function not implemented (-38).
             */
            val surfaceSizes = map.getOutputSizes(SurfaceHolder::class.java)?.toList().orEmpty()
            if (surfaceSizes.isEmpty()) {
                Log.e(TAG, "Camera $cameraId advertises no SurfaceHolder preview sizes")
                return
            }

            val displayWidth = previewSurface.width.coerceAtLeast(1)
            val displayHeight = previewSurface.height.coerceAtLeast(1)

            previewSize = choosePreviewSize(
                surfaceSizes,
                displayWidth,
                displayHeight
            )

            val selectedSize = previewSize
                ?: run {
                    Log.e(TAG, "Unable to select supported preview size for camera $cameraId")
                    return
                }

            previewSurface.holder.setFixedSize(
                selectedSize.width,
                selectedSize.height
            )

            cameraOpening = true

            manager.openCamera(
                cameraId,
                object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                        cameraOpening = false

                        if (!activityResumed || !surfaceReady ||
                            !previewSurface.holder.surface.isValid
                        ) {
                            camera.close()
                            return
                        }

                        cameraDevice = camera
                        createPreviewSession()
                    }

                    override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                        cameraOpening = false
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                    }

                    override fun onError(
                        camera: android.hardware.camera2.CameraDevice,
                        error: Int
                    ) {
                        cameraOpening = false
                        Log.e(TAG, "Camera open error cameraId=$cameraId error=$error")
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                    }

                    override fun onClosed(camera: android.hardware.camera2.CameraDevice) {
                        if (cameraDevice === camera) {
                            cameraDevice = null
                        }
                        if (activityResumed && surfaceReady) {
                            previewSurface.post { startPreviewIfReady() }
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: SecurityException) {
            cameraOpening = false
            Log.e(TAG, "Camera permission revoked", e)
        } catch (e: Exception) {
            cameraOpening = false
            Log.e(TAG, "Unable to open camera $cameraId", e)
        }
    }

    private fun choosePreviewSize(
        sizes: List<Size>,
        viewWidth: Int,
        viewHeight: Int
    ): Size? {
        if (sizes.isEmpty()) return null

        val targetRatio = viewWidth.toDouble() / viewHeight.toDouble()

        // Prefer the largest advertised preview stream at or below the design
        // limit, with aspect ratio as the primary criterion.
        val bounded = sizes.filter {
            it.width <= 1920 && it.height <= 1080
        }

        val pool = if (bounded.isNotEmpty()) bounded else sizes

        return pool.minWithOrNull(
            compareBy<Size> {
                kotlin.math.abs(
                    (it.width.toDouble() / it.height.toDouble()) - targetRatio
                )
            }.thenByDescending {
                it.width.toLong() * it.height.toLong()
            }
        )
    }

    private fun createPreviewSession() {
        val camera = cameraDevice ?: return
        if (!activityResumed || !surfaceReady) return

        val surface = previewSurface.holder.surface
        if (!surface.isValid) return

        try {
            val requestBuilder =
                camera.createCaptureRequest(
                    android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
                )

            requestBuilder.addTarget(surface)

            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(camera.id)

            val afModes =
                characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            if (afModes?.contains(
                    CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                ) == true
            ) {
                requestBuilder.set(
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }

            val aeModes =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            if (aeModes?.contains(
                    CameraCharacteristics.CONTROL_AE_MODE_ON
                ) == true
            ) {
                requestBuilder.set(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
                )
            }

            camera.createCaptureSession(
                listOf(surface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(
                        session: android.hardware.camera2.CameraCaptureSession
                    ) {
                        if (!activityResumed ||
                            !surfaceReady ||
                            cameraDevice !== camera ||
                            !surface.isValid
                        ) {
                            session.close()
                            return
                        }

                        previewSession = session

                        try {
                            session.setRepeatingRequest(
                                requestBuilder.build(),
                                null,
                                cameraHandler
                            )
                            Log.i(
                                TAG,
                                "Preview started camera=${camera.id} size=${previewSize?.width}x${previewSize?.height}"
                            )
                        } catch (e: android.hardware.camera2.CameraAccessException) {
                            Log.e(TAG, "Unable to start repeating preview", e)
                            session.close()
                            if (previewSession === session) previewSession = null
                            camera.close()
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "Preview session became invalid", e)
                            session.close()
                            if (previewSession === session) previewSession = null
                        }
                    }

                    override fun onConfigureFailed(
                        session: android.hardware.camera2.CameraCaptureSession
                    ) {
                        Log.e(
                            TAG,
                            "Preview session configuration failed camera=${camera.id} " +
                                "size=${previewSize?.width}x${previewSize?.height}"
                        )
                        session.close()
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create preview session", e)
        }
    }

    private fun closePreview() {
        try {
            previewSession?.stopRepeating()
        } catch (_: Exception) {
        }

        try {
            previewSession?.abortCaptures()
        } catch (_: Exception) {
        }

        try {
            previewSession?.close()
        } catch (_: Exception) {
        }
        previewSession = null

        val device = cameraDevice
        cameraDevice = null

        if (device != null) {
            try {
                device.close()
            } catch (_: Exception) {
            }
        }

        // The open callback can still arrive after close() was requested.
        // cameraOpening is cleared by onOpened/onError/onDisconnected.
    }

    private fun stopCameraThread() {
        closePreview()
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        cameraThread = null
        cameraHandler = null
        cameraOpening = false
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        startPreviewIfReady()
    }

    override fun onPause() {
        activityResumed = false
        closePreview()
        super.onPause()
    }

    override fun onDestroy() {
        activityResumed = false
        orientationListener?.disable()
        orientationListener = null
        stopCameraThread()
        super.onDestroy()
    }

    private fun jpegSizes(map: StreamConfigurationMap?): List<Size> {
        return map
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.toList()
            ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
            ?: emptyList()
    }

    private fun areaMp(size: Size?): Double {
        if (size == null || size.width <= 0 || size.height <= 0) return 0.0
        return size.width.toLong() * size.height.toLong() / 1_000_000.0
    }

    private fun formatMp(mp: Double): String =
        "%.1f".format(java.util.Locale.US, mp)

    private fun facingLabel(facing: Int?): String =
        when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }

    private fun logCameraDetails(camera: CameraDetails) {
        Log.i(
            TAG,
            """
            Camera ${camera.cameraId}
              facing=${camera.facing}
              logicalMultiCamera=${camera.isLogicalMultiCamera}
              physicalCameraIds=${camera.physicalCameraIds}
              normalSensor=${camera.sensorWidth}x${camera.sensorHeight} (${formatMp(camera.normalSensorMp)} MP)
              maximumSensor=${formatMp(camera.maximumSensorMp)} MP
              ultraHighResolutionSensor=${camera.ultraHighResolutionSensor}
              resolvedSensor=${formatMp(camera.sensorMp)} MP
              largestNormalJPEG=${formatMp(camera.normalJpegSizes.maxOfOrNull { areaMp(it) } ?: 0.0)} MP
              largestMaximumJPEG=${formatMp(camera.maximumJpegSizes.maxOfOrNull { areaMp(it) } ?: 0.0)} MP
              focalLengths=${camera.focalLengths}
              hardware=${camera.hardwareLevel}
              orientation=${camera.sensorOrientation}
              flash=${camera.flashAvailable}
              maxDigitalZoom=${camera.maxDigitalZoom}
              normalJPEG=${camera.normalJpegSizes.joinToString { "${it.width}x${it.height}" }}
              maximumJPEG=${camera.maximumJpegSizes.joinToString { "${it.width}x${it.height}" }}
              supportedFixedTiers=${camera.supportedTiers}
              selectedPrimaryPhysicalId=${camera.selectedPhysicalId}
              physicalBasisSensor=${formatMp(camera.sensorMp)} MP
              defaultFixedTier=${camera.defaultTierMp} MP
              physicalDetails=${camera.physicalDetails.joinToString { p ->
                  "${p.cameraId}:${formatMp(p.sensorMp)}MP:f=${p.primaryFocalLengthMm}mm"
              }}
            """.trimIndent()
        )
    }

    data class CameraDetails(
        val cameraId: String,
        val facing: String,
        val isPhysical: Boolean,
        val isLogicalMultiCamera: Boolean,
        val physicalCameraIds: List<String>,
        val selectedPhysicalId: String?,
        val physicalDetails: List<CameraDetails>,
        val sensorWidth: Int,
        val sensorHeight: Int,
        val sensorMp: Double,
        val normalSensorMp: Double,
        val maximumSensorMp: Double,
        val ultraHighResolutionSensor: Boolean,
        val hardwareLevel: String,
        val sensorOrientation: Int,
        val flashAvailable: Boolean,
        val focalLengths: List<Float>,
        val normalJpegSizes: List<Size>,
        val maximumJpegSizes: List<Size>,
        val maxDigitalZoom: Float,
        val defaultTierMp: Int,
        val supportedTiers: List<Int>
    ) {
        val primaryFocalLengthMm: Float
            get() = focalLengths.minOrNull() ?: Float.NaN
    }
}
