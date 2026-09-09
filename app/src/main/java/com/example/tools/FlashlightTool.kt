package com.example.tools

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import org.json.JSONObject

/**
 * Tool for toggling or controlling the device flashlight / torch.
 */
class FlashlightTool : PhoneTool {
    override val name: String = "control_flashlight"
    override val description: String =
        "Turns device flashlight/torch on or off, or toggles it (actions: 'on', 'off', 'toggle')."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["on", "off", "toggle"],
                    "description": "Flashlight action: 'on' to enable torch, 'off' to disable torch, or 'toggle'"
                }
            },
            "required": ["action"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val action = try {
            val json = JSONObject(argumentsJson)
            json.optString("action", "toggle").lowercase()
        } catch (e: Exception) {
            "toggle"
        }

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            return ToolExecutionResult(
                success = false,
                message = "Device has no camera flashlight hardware.",
                speechResponse = "Sir, this device does not appear to have a camera flash."
            )
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolExecutionResult(
                success = false,
                message = "CameraManager unavailable.",
                speechResponse = "Sir, cannot access flashlight system."
            )

        return try {
            val cameraId = getCameraIdWithFlash(cameraManager)
                ?: return ToolExecutionResult(
                    success = false,
                    message = "No camera with flash found.",
                    speechResponse = "Sir, flash hardware could not be located."
                )

            val turnOn = when (action) {
                "on" -> true
                "off" -> false
                else -> !isTorchOn
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.setTorchMode(cameraId, turnOn)
                isTorchOn = turnOn
                val statusText = if (turnOn) "ON" else "OFF"
                val speech = if (turnOn) "Flashlight turned on, sir." else "Flashlight turned off, sir."
                ToolExecutionResult(
                    success = true,
                    message = "Flashlight turned $statusText",
                    speechResponse = speech
                )
            } else {
                ToolExecutionResult(
                    success = false,
                    message = "Torch control requires Android M+",
                    speechResponse = "Sir, OS version does not support direct torch mode."
                )
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException: ${e.message}")
            ToolExecutionResult(
                success = false,
                message = "Failed to access camera flash: ${e.message}",
                speechResponse = "Sir, flashlight is currently occupied by another application."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}")
            ToolExecutionResult(
                success = false,
                message = "Flashlight error: ${e.message}",
                speechResponse = "Sir, could not toggle the flashlight."
            )
        }
    }

    private fun getCameraIdWithFlash(cameraManager: CameraManager): String? {
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            // Fallback: any camera with flash
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    return id
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding flash camera id: ${e.message}")
        }
        return null
    }

    companion object {
        private const val TAG = "FlashlightTool"
        var isTorchOn: Boolean = false
            private set

        fun setTorchDirectly(context: Context, enable: Boolean): Boolean {
            return try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
                val id = cameraManager.cameraIdList.firstOrNull {
                    cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cameraManager.setTorchMode(id, enable)
                    isTorchOn = enable
                    true
                } else false
            } catch (e: Exception) {
                Log.e(TAG, "Direct torch error: ${e.message}")
                false
            }
        }
    }
}
