package com.smsserver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startWebhookServer()
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
        refreshUI()
    }

    // -----------------------------------------------------------------------
    // UI setup
    // -----------------------------------------------------------------------

    private fun setupUI() {
        // Toggle server on/off
        binding.switchServer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestPermissionsAndStart()
            } else {
                stopWebhookServer()
            }
        }

        // Copy API key to clipboard
        binding.btnCopyApiKey.setOnClickListener {
            val apiKey = prefsManager.apiKey ?: ""
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("API Key", apiKey))
            Toast.makeText(this, getString(R.string.api_key_copied), Toast.LENGTH_SHORT).show()
        }

        // Regenerate API key (only when server is stopped)
        binding.btnRegenApiKey.setOnClickListener {
            if (prefsManager.isServerEnabled) {
                Toast.makeText(this, getString(R.string.stop_server_first), Toast.LENGTH_SHORT).show()
            } else {
                regenerateApiKey()
                refreshUI()
            }
        }

        // Save port setting
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

        // Save relay URL setting
        binding.btnSaveRelay.setOnClickListener {
            val url = binding.etRelayUrl.text.toString().trim()
            if (url.isBlank() || (!url.startsWith("wss://") && !url.startsWith("ws://"))) {
                Toast.makeText(this, getString(R.string.relay_invalid), Toast.LENGTH_SHORT).show()
            } else {
                prefsManager.relayUrl = url
                Toast.makeText(this, getString(R.string.relay_saved), Toast.LENGTH_SHORT).show()
                // Restart server to apply new relay URL
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

        binding.tvApiKey.text = apiKey
        binding.etPort.setText(port.toString())
        binding.etRelayUrl.setText(relayUrl)
        binding.switchServer.isChecked = serverEnabled

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
        }
    }

    // -----------------------------------------------------------------------
    // Server lifecycle
    // -----------------------------------------------------------------------

    private fun requestPermissionsAndStart() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startWebhookServer()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
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

        val intent = WebhookService.buildStopIntent(this)
        startService(intent)

        refreshUI()
        Toast.makeText(this, getString(R.string.server_stopped_msg), Toast.LENGTH_SHORT).show()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
