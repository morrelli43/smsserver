package com.smsserver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import java.net.HttpURLConnection
import java.net.URL
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smsserver.databinding.ActivityMainBinding
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }.toTypedArray()

        private const val API_KEY_LENGTH = 32
        private val API_KEY_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    }

    private lateinit var binding: ActivityMainBinding

    private val prefsManager by lazy {
        PrefsManager(this).apply { migrateIfNeeded() }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PrefsManager.KEY_CONNECTION_STATUS || key == PrefsManager.KEY_SERVER_ENABLED) {
            runOnUiThread { refreshUI() }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            checkOverlayPermissionAndStart()
        } else {
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
            binding.switchServer.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureApiKey()
        setupUI()
        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("smsserver_prefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        refreshUI()
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("smsserver_prefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun setupUI() {
        binding.switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestPermissionsAndStart()
            } else {
                stopWebhookServer()
            }
        }

        binding.btnCopyApiKey.setOnClickListener {
            val apiKey = prefsManager.apiKey ?: ""
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("API Key", apiKey))
            Toast.makeText(this, getString(R.string.api_key_copied), Toast.LENGTH_SHORT).show()
        }

        binding.btnRegenApiKey.setOnClickListener {
            if (prefsManager.isServerEnabled) {
                Toast.makeText(this, getString(R.string.stop_server_first), Toast.LENGTH_SHORT).show()
            } else {
                regenerateApiKey()
                refreshUI()
            }
        }

        binding.btnSavePort.setOnClickListener {
            val portText = binding.etPort.text.toString().trim()
            val port = portText.toIntOrNull()
            if (port == null || port !in 1024..65535) {
                Toast.makeText(this, getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
            } else {
                prefsManager.port = port
                Toast.makeText(this, getString(R.string.port_saved), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveRelay.setOnClickListener {
            val url = binding.etRelayUrl.text.toString().trim()
            if (url.isBlank() || (!url.startsWith("wss://") && !url.startsWith("ws://"))) {
                Toast.makeText(this, getString(R.string.relay_invalid), Toast.LENGTH_SHORT).show()
            } else {
                prefsManager.relayUrl = url
                Toast.makeText(this, getString(R.string.relay_saved), Toast.LENGTH_SHORT).show()
                if (prefsManager.isServerEnabled) {
                    stopWebhookServer()
                    startWebhookServer()
                }
            }
        }
    }

    private fun refreshUI() {
        val apiKey = prefsManager.apiKey ?: ""
        val port = prefsManager.port
        val serverEnabled = prefsManager.isServerEnabled
        val relayUrl = prefsManager.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL
        val connStatus = prefsManager.connectionStatus

        binding.tvApiKey.text = apiKey
        binding.etPort.setText(port.toString())
        binding.etRelayUrl.setText(relayUrl)
        binding.switchServer.isChecked = serverEnabled

        when (connStatus) {
            "connected" -> {
                binding.tvConnectionStatus.text = getString(R.string.status_connected)
                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running))
            }
            "error" -> {
                binding.tvConnectionStatus.text = getString(R.string.status_error)
                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped))
            }
            else -> {
                binding.tvConnectionStatus.text = getString(R.string.status_offline)
                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped))
            }
        }

        if (serverEnabled) {
            val ip = getWifiIpAddress()
            val url = "http://$ip:$port"
            binding.tvServerUrl.text = url
            binding.tvServerUrl.visibility = View.VISIBLE
            binding.tvServerStatus.setText(R.string.server_running)
            binding.tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running))
            fetchAndShowExternalIp(port)
        } else {
            binding.tvServerUrl.visibility = View.GONE
            binding.tvExternalUrl.visibility = View.GONE
            binding.tvServerStatus.setText(R.string.server_stopped)
            binding.tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped))
            binding.tvConnectionStatus.text = getString(R.string.status_offline)
            binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped))
        }
    }

    private fun requestPermissionsAndStart() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            checkOverlayPermissionAndStart()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkOverlayPermissionAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Appear on top' to allow background dialing", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            binding.switchServer.isChecked = false
        } else {
            startWebhookServer()
        }
    }

    private fun startWebhookServer() {
        val apiKey = prefsManager.apiKey ?: ""
        val port = prefsManager.port
        val relayUrl = prefsManager.relayUrl ?: PrefsManager.DEFAULT_RELAY_URL
        prefsManager.isServerEnabled = true
        val intent = WebhookService.buildStartIntent(this, apiKey, port, relayUrl)
        startForegroundService(intent)
        refreshUI()
        Toast.makeText(this, getString(R.string.server_started, port), Toast.LENGTH_SHORT).show()
    }

    private fun stopWebhookServer() {
        prefsManager.isServerEnabled = false
        prefsManager.connectionStatus = "offline"
        val intent = WebhookService.buildStopIntent(this)
        startService(intent)
        refreshUI()
        Toast.makeText(this, getString(R.string.server_stopped_msg), Toast.LENGTH_SHORT).show()
    }

    private fun ensureApiKey() {
        if (prefsManager.apiKey.isNullOrBlank()) {
            regenerateApiKey()
        }
    }

    private fun regenerateApiKey() {
        val rng = SecureRandom()
        val key = (1..API_KEY_LENGTH)
            .map { API_KEY_CHARS[rng.nextInt(API_KEY_CHARS.size)] }
            .joinToString("")
        prefsManager.apiKey = key
    }

    private fun getWifiIpAddress(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) "localhost" else {
                ((ip and 0xff).toString() + "." +
                        (ip shr 8 and 0xff) + "." +
                        (ip shr 16 and 0xff) + "." +
                        (ip shr 24 and 0xff))
            }
        } catch (e: Exception) {
            "localhost"
        }
    }

    private fun fetchAndShowExternalIp(port: Int) {
        binding.tvExternalUrl.text = getString(R.string.external_url_fetching)
        binding.tvExternalUrl.visibility = View.VISIBLE
        Thread {
            val externalIp = try {
                val conn = (URL("https://api.ipify.org").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }
                conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    .also { conn.disconnect() }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (externalIp != null) {
                    binding.tvExternalUrl.text = getString(R.string.external_url_label, externalIp, port)
                } else {
                    binding.tvExternalUrl.visibility = View.GONE
                }
            }
        }.also { it.isDaemon = true }.start()
    }
}
