package com.jil2.vpnchecker

import android.os.Build
import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.NetworkInterface
import androidx.core.content.edit


class VpnMonitorService : Service() {

    private var wasVpnUp = false
    private val channelMonitoringId = "VpnMonitorChannel_Silent"
    private val channelAlertId = "VpnAlertChannel_Beep"
    private val alertId = 1001


    private val handlerThread: android.os.HandlerThread by lazy {
        android.os.HandlerThread("VpnMonitorThread").also { it.start() }
    }
    private val handler: android.os.Handler by lazy {
        android.os.Handler(handlerThread.looper)
    }

    private lateinit var monitorRunnable: Runnable


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("vpn_monitor", MODE_PRIVATE)
        wasVpnUp = prefs.getBoolean("was_vpn_up", false)


        createNotificationChannels()

        // Check current state immediately
        val isUp = NetworkInterface.getNetworkInterfaces()?.asSequence()?.any {
            isVpnInterface(it)
        } ?: false

        // Set the initial notification based on real-time status
        val notification = if (isUp) getVpnAlertNotification() else getScanningNotification()

        startForeground(alertId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        startMonitoring()
        return START_STICKY
    }


    private fun startMonitoring() {
        monitorRunnable = object : Runnable {
            override fun run() {
                val isVpnUp = NetworkInterface.getNetworkInterfaces()?.asSequence()?.any {
                    isVpnInterface(it)
                } ?: false

                if (isVpnUp && !wasVpnUp) {
                    sendAlert()
                    wasVpnUp = true
                } else if (!isVpnUp && wasVpnUp) {
                    clearAlert()
                    wasVpnUp = false
                }

                // Schedule the NEXT run in 5 seconds
                // This prevents "bursts" because it only schedules one at a time
                handler.postDelayed(this, 5000)
            }
        }
        handler.post(monitorRunnable)
    }

    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun isVpnInterface(ni: NetworkInterface): Boolean {
        return ni.isUp && ( ni.name.startsWith("tun") || ni.name.startsWith("ppp") || ni.name.startsWith("wg") )
    }

    private fun sendAlert() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(alertId, getVpnAlertNotification())

    }

    private fun getVpnAlertNotification(): Notification {
        return NotificationCompat.Builder(this, channelAlertId)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setContentTitle("⚠️ VPN DETECTED")
            .setContentText("The tunnel interface is currently active!")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setOngoing(true)
            .build()
    }

    private fun clearAlert() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(alertId, getScanningNotification())
    }

    private fun getScanningNotification(): Notification {
        return NotificationCompat.Builder(this, channelMonitoringId)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setContentTitle("VPN Monitor Active")
            .setContentText("Scanning for active tunnels...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 1. Silent Channel for the constant monitor
            val monitorChannel = NotificationChannel(
                channelMonitoringId,
                "VPN Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )

            // 2. Loud Channel for the VPN alert
            val alertChannel = NotificationChannel(
                channelAlertId,
                "VPN Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500)
            }

            manager.createNotificationChannel(monitorChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }


    override fun onDestroy() {
        // Crucial: stop the loop when the service is killed
        getSharedPreferences("vpn_monitor", MODE_PRIVATE)
            .edit {
                putBoolean("was_vpn_up", wasVpnUp)
            }
        if (::monitorRunnable.isInitialized) {
            handler.removeCallbacks(monitorRunnable)
        }
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
