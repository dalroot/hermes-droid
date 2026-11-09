package org.nous.hermesdroid.modules

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject

class RadioSilenceModule(private val context: Context) {

    fun setRadioSilence(enable: Boolean): JSONObject {
        val details = JSONObject()

        // 1. Wi-Fi
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.isWifiEnabled = !enable
            details.put("wifi_disabled", enable)
        } catch (e: Exception) {
            details.put("wifi_error", e.message)
        }

        // 2. Bluetooth
        try {
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            if (btAdapter != null) {
                if (enable && btAdapter.isEnabled) {
                    btAdapter.disable()
                } else if (!enable && !btAdapter.isEnabled) {
                    btAdapter.enable()
                }
                details.put("bluetooth_disabled", enable)
            }
        } catch (e: Exception) {
            details.put("bluetooth_error", e.message)
        }

        // 3. Airplane mode & Cellular via Shell fallback
        try {
            val state = if (enable) "enable" else "disable"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd connectivity airplane-mode $state"))
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "svc data $state"))
            details.put("cellular_toggle_attempted", true)
        } catch (e: Exception) {
            details.put("cellular_error", e.message)
        }

        return JSONObject().apply {
            put("success", true)
            put("radio_silence_active", enable)
            put("details", details)
        }
    }
}
