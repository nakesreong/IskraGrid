package com.gemini.iskra

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var engine: IskraEngine
    private val bgExecutor = Executors.newCachedThreadPool()

    private val stateListener: ( org.json.JSONObject ) -> Unit = { snap ->
        val jsonStr = snap.toString()
        runOnUiThread {
            try {
                webView.evaluateJavascript("if(window.onIskraState) window.onIskraState($jsonStr);", null)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        engine = IskraEngine.getInstance(this)

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val svcIntent = Intent(this, IskraForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.allowFileAccess = true
            settings.textZoom = 100
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(IskraBridge(), "IskraNative")
        }

        setContentView(webView)

        engine.onStateUpdated = stateListener

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        engine.onStateUpdated = stateListener
        try { webView.onResume() } catch (_: Exception) {}
        val snapStr = engine.buildStateSnapshot().toString()
        webView.post {
            try {
                webView.evaluateJavascript("if(window.onIskraState) window.onIskraState($snapStr);", null)
            } catch (_: Exception) {}
        }
        bgExecutor.execute {
            engine.pollAllSync()
        }
    }

    override fun onDestroy() {
        if (engine.onStateUpdated === stateListener) {
            engine.onStateUpdated = null
        }
        super.onDestroy()
    }

    private fun hapticTick() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }

    inner class IskraBridge {
        @JavascriptInterface
        fun getStateJson(): String {
            return engine.buildStateSnapshot().toString()
        }

        @JavascriptInterface
        fun forceRefresh() {
            hapticTick()
            bgExecutor.execute {
                engine.pollAllSync()
            }
        }

        @JavascriptInterface
        fun refreshDtek() {
            hapticTick()
            engine.forceFetchDtekSchedule()
        }

        @JavascriptInterface
        fun silentPoll() {
            bgExecutor.execute {
                engine.pollAllSync()
            }
        }

        @JavascriptInterface
        fun toggleOukitelAc(on: Boolean) {
            hapticTick()
            bgExecutor.execute {
                engine.oukitel.setAcSwitch(on)
                engine.notifySnapshot()
                Thread.sleep(1500)
                engine.pollAllSync()
            }
        }

        @JavascriptInterface
        fun toggleOukitelDc(on: Boolean) {
            hapticTick()
            bgExecutor.execute {
                engine.oukitel.setDcSwitch(on)
                engine.notifySnapshot()
                Thread.sleep(1500)
                engine.pollAllSync()
            }
        }

        @JavascriptInterface
        fun toggleOukitelLed(mode: Int) {
            hapticTick()
            bgExecutor.execute {
                engine.oukitel.setLedStatus(mode)
                engine.notifySnapshot()
                Thread.sleep(1500)
                engine.pollAllSync()
            }
        }

        @JavascriptInterface
        fun setOukitelChargeLimit(limit: Int) {
            hapticTick()
            bgExecutor.execute {
                engine.oukitel.setAcChargingLimit(limit)
                engine.notifySnapshot()
                Thread.sleep(2500)
                engine.pollAllSync()
            }
        }

        @JavascriptInterface
        fun startSmartRatoStop(cooldownSec: Int) {
            hapticTick()
            engine.startSmartRatoShutdown(cooldownSec)
        }

        @JavascriptInterface
        fun killRatoNow() {
            hapticTick()
            engine.killRatoImmediately()
        }

        @JavascriptInterface
        fun cancelRatoStop() {
            hapticTick()
            engine.cancelSmartShutdown()
        }

        @JavascriptInterface
        fun toggleSmartLifeSwitch(deviceId: String, code: String, on: Boolean) {
            hapticTick()
            bgExecutor.execute {
                engine.smartLife.sendSwitchCommand(deviceId, code, on)
                Thread.sleep(900)
                engine.pollAllSync()
            }
        }

        @JavascriptInterface
        fun resetHistory() {
            hapticTick()
            engine.resetTelemetryHistory()
        }

        @JavascriptInterface
        fun saveGenConfig(jsonStr: String) {
            hapticTick()
            engine.saveGeneratorConfig(jsonStr)
        }

        @JavascriptInterface
        fun markOilChanged(usedHoursSinceChange: Double) {
            hapticTick()
            engine.markOilChanged(usedHoursSinceChange)
        }

        @JavascriptInterface
        fun markSparkChanged(usedHoursSinceChange: Double) {
            hapticTick()
            engine.markSparkChanged(usedHoursSinceChange)
        }

        @JavascriptInterface
        fun requestUnrestrictedBattery() {
            hapticTick()
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (_: Exception) {}
        }
    }
}
