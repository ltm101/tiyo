package com.koyo.screenwarden

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class ActivityFragment : Fragment(R.layout.fragment_activity), SensorEventListener {

    private lateinit var stepCountText: TextView
    private lateinit var stepProgress: ProgressBar
    private lateinit var stepGoalText: TextView
    private lateinit var stepDistance: TextView
    private lateinit var stepKcal: TextView
    private lateinit var permissionCard: View
    private lateinit var grantActivityBtn: Button
    private lateinit var noSensorText: TextView

    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var sensorReady = false
    private var lastSteps = 0

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) tryStartSensor()
        updateUI()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        stepCountText = view.findViewById(R.id.step_count_text)
        stepProgress = view.findViewById(R.id.step_progress)
        stepGoalText = view.findViewById(R.id.step_goal_text)
        stepDistance = view.findViewById(R.id.step_distance)
        stepKcal = view.findViewById(R.id.step_kcal)
        permissionCard = view.findViewById(R.id.step_permission_card)
        grantActivityBtn = view.findViewById(R.id.grant_activity_btn)
        noSensorText = view.findViewById(R.id.step_no_sensor)

        grantActivityBtn.setOnClickListener {
            permLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor == null) {
            noSensorText.visibility = View.VISIBLE
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            tryStartSensor()
            updateUI()
        } else {
            unregisterSensor()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterSensor()
    }

    private fun tryStartSensor() {
        if (stepSensor == null) return
        if (!hasActivityPermission()) {
            sensorReady = false
            return
        }
        sensorManager?.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorReady = true
    }

    private fun unregisterSensor() {
        try { sensorManager?.unregisterListener(this) } catch (_: Exception) {}
        sensorReady = false
    }

    // ---- SensorEventListener ----

    override fun onSensorChanged(event: SensorEvent) {
        val total = event.values[0].toInt()
        lastSteps = StepCounterCollector.onStepEvent(requireContext(), total)
        updateUI()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- UI ----

    private fun updateUI() {
        if (!hasActivityPermission()) {
            permissionCard.visibility = View.VISIBLE
            stepCountText.text = "--"
            stepProgress.progress = 0
            stepGoalText.text = "需要授权后才能读取步数"
            stepDistance.text = "-- km"
            stepKcal.text = "-- kcal"
            return
        }

        permissionCard.visibility = View.GONE

        if (stepSensor == null) {
            stepCountText.text = "--"
            return
        }

        val steps = if (sensorReady) lastSteps else StepCounterCollector.getTodaySteps(requireContext())
        val goal = 10000

        stepCountText.text = String.format("%,d", steps)
        stepProgress.progress = steps.coerceAtMost(goal)
        stepGoalText.text = String.format("目标 %,d 步  %d%%", goal, (steps * 100 / goal).coerceAtMost(100))
        stepDistance.text = "${StepCounterCollector.stepsToKm(steps)} km"
        stepKcal.text = "${StepCounterCollector.stepsToKcal(steps)} kcal"
    }

    private fun hasActivityPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }
}
