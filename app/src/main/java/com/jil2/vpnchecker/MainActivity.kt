package com.jil2.vpnchecker

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Deferred


class MainActivity : AppCompatActivity() {

    private var sbNetwork: String = "Perform scan to see network data..."
    private var sbApps: String = "Perform scan to see app data..."
    private var sbCap: String = "Perform scan to see app data..."
    private var sbNet: String = "Perform scan to see app data..."
    private var sbPorts: String = "Perform scan to see ports..."
    private lateinit var viewPager: ViewPager2
    private lateinit var btnCheck: Button

    private val ipEndpoints = listOf(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://icanhazip.com"
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Permissions Check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        btnCheck = findViewById(R.id.btnCheck)
        val txtQuickStatus = findViewById<TextView>(R.id.txtQuickStatus)

        // 2. Setup Tabs
        val adapter = TabAdapter(this)
        viewPager.adapter = adapter
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when(position) {
                0 -> "Network"
                1 -> "Apps"
                2 -> "Raw Cap"
                3 -> "Raw Net"
                else -> "Ports"
            }
        }.attach()

        tabLayout.setTabTextColors(
            "#757575".toColorInt(),
            "#000000".toColorInt()
        )

        // 3. Button Logic
        btnCheck.setOnClickListener {

            if (!isServiceRunning()) {
                startForegroundService(Intent(this, VpnMonitorService::class.java))
            }

            refreshData()


            // --- Combined VPN status using 2 signals ---
            val hasTun = NetworkInterface.getNetworkInterfaces()?.asSequence()?.any {
                (it.name.startsWith("tun") || it.name.startsWith("ppp") || it.name.startsWith("wg")) && it.isUp
            } ?: false

            val hasTransport = run {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }

            val vpnActive = hasTun || hasTransport

            txtQuickStatus.text = if (vpnActive) "🛡️ VPN ACTIVE" else "⚠️ VPN IS NOT ACTIVE"
            txtQuickStatus.setTextColor(if (vpnActive) "#4CAF50".toColorInt() else Color.RED)
        }
    }

    // Checks own service — getRunningServices() is reliable for own app on all API levels
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == VpnMonitorService::class.java.name
        }
    }



    // --- Build ALL Interfaces List ---
    private fun buildInterfaceString(): String {
        val sb = StringBuilder()
        sb.append("🌐 ALL INTERFACES & IPs\n━━━━━━━━━━━━\n")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces?.hasMoreElements() == true) {
                val ni = interfaces.nextElement()
                if (ni.isUp) {
                    val prefix = if (ni.name.startsWith("tun") || ni.name.startsWith("ppp") || ni.name.startsWith("wg")) "🚩 " else "• "
                    sb.append("$prefix${ni.name}\n")
                    ni.inetAddresses.asSequence().forEach { sb.append("  └ ${it.hostAddress}\n") }
                }
            }
        } catch (e: Exception) {
            sb.append("Error: ${e.message}\n")
        }
        return sb.toString()
    }


    // --- GET VPN Interface (with different method) ---
    private fun findVpnInterfaceString(): String? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnNetwork = cm.allNetworks.firstOrNull { network ->
            val iface = cm.getLinkProperties(network)?.interfaceName ?: return@firstOrNull false
            iface.startsWith("tun") || iface.startsWith("ppp") || iface.startsWith("wg")
        }
        return vpnNetwork
            ?.let { cm.getLinkProperties(it)?.interfaceName }
    }


    // --- Build VPN interface list ---
    private fun buildVpnInterfaceString(): String {
        val sb = StringBuilder()
        sb.append("\n⚙️ VPN INTERFACE\n━━━━━━━━━━━━\n")
        try {
            val ifaceName = findVpnInterfaceString()
            if (ifaceName != null) {
                sb.append("🔒 $ifaceName\n")
            } else {
                sb.append("❌ IF was not found\n")
            }
        } catch (e: Exception) {
            sb.append("Error: ${e.message}\n")
        }
        return sb.toString()
    }


    // --- GET Capabilities ---
    private fun getCapsString(): String {
        val sb = StringBuilder()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNet)

        sb.append("\n🌐 VPN TRANSPORT STATUS\n━━━━━━━━━━━━\n")

        if (caps != null) {
            // Check for VPN transport specifically
            val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val rawCaps = caps?.toString() ?: "None"
            val formattedCaps = rawCaps
                .replace(" [", "\n\n[")
                .replace(" Transports: ", "🌐 TRANSPORTS:\n  • ")
                .replace(" Capabilities: ", "\n\n🛡️ CAPABILITIES:\n  • ")
                .replace(Regex("Link.*"), "")
                .replace(Regex("mSubId.*"), "")

            val wellStructuredCaps = formattedCaps
                .replace(Regex("&|, "), "\n  • ")
                .replace("[", "")
                .replace("]", "")

            if (isVpn) {
                sb.append("✅ VPN Transport: ACTIVE\n\n")
                sb.append("$wellStructuredCaps\n")
            } else {
                sb.append("❌ VPN Transport: NOT DETECTED\n\n")
                sb.append("$wellStructuredCaps\n")
            }

        } else {
            sb.append("⚠️ No Active Network Detected.\n")
        }
        return sb.toString()
    }


    // --- Check running services for VPN-like entries ---
    private fun checkVpnServiceString(): String {
        val sb = StringBuilder("\n⚙️ VPN SERVICES\n━━━━━━━━━━━━\n")

        // Part 1: Declared VPN services (works API 26+)
        sb.append("  📦 Declared VPN services:\n")
        val intent = Intent(android.net.VpnService::class.java.name)
        val declared = packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        if (declared.isEmpty()) {
            sb.append("    • None found\n")
        } else {
            declared.forEach {
                sb.append("    • ${it.serviceInfo.packageName}\n")
            }
        }

        // Part 2: Running services (only reliable on API < 26)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sb.append("\n  🔄 Running VPN services:\n")
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val running = am.getRunningServices(100).filter {
                it.service.className.lowercase().let { cls ->
                    cls.contains("vpn") || cls.contains("tunnel") || cls.contains("proxy")
                }
            }
            if (running.isEmpty()) {
                sb.append("    • None detected\n")
            } else {
                running.forEach { sb.append("    • ${it.service.packageName}\n") }
            }
        } else {
            sb.append("\n  🔄 Running services: N/A (API 26+)\n")
        }
        return sb.toString()
    }


    // --- DNS leak detection ---
    private fun checkDnsServersString(): String {
        val sb = StringBuilder("\n🔍 DNS SERVERS\n━━━━━━━━━━━━\n")
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            var found = false
            cm.allNetworks.forEach { network ->
                val props = cm.getLinkProperties(network) ?: return@forEach
                val iface = props.interfaceName ?: "unknown"
                props.dnsServers.forEach { dns ->
                    val isPrivate = dns.isSiteLocalAddress || dns.isLoopbackAddress || dns.isLinkLocalAddress
                    val flag = if (isPrivate) "🔒" else "⚠️"
                    sb.append("  • $iface → ${dns.hostAddress} $flag\n")
                    found = true
                }
            }
            if (!found) sb.append("  • No DNS servers found\n")
            sb.toString()
        } catch (e: Exception) {
            sb.append("  • Error: ${e.message}\n")
            sb.toString()
        }
    }


    private fun refreshData() {
        val nSb = StringBuilder()
        val interfaceString  = buildInterfaceString()
        val vpnIfaceString   = buildVpnInterfaceString()
        val capsString       = getCapsString()
        val vpnServiceString = checkVpnServiceString()
        val dnsString        = checkDnsServersString()

        // --- TAB 1: NETWORK INITIAL SETUP (sync placeholder) ---

        btnCheck.isEnabled = false
        btnCheck.text = "Scanning..."
        btnCheck.setTextColor("#29402d".toColorInt())
        btnCheck.setBackgroundColor("#b4f0bd".toColorInt())

        // Add Interfaces and IPs
        nSb.append(interfaceString)

        // Add VPN Interface
        nSb.append(vpnIfaceString)

        // Add Capabilities
        nSb.append(capsString)

        // Add VPN service check
        nSb.append(vpnServiceString)

        // Add DNS info
        nSb.append(dnsString)

        // Placeholders for IP resolving
        val hasVpnInterface = findVpnInterfaceString() !=null
        nSb.append("\n🌍 PUBLIC IP CHECK\n━━━━━━━━━━━━\n")
        nSb.append("Direct IP: Resolving...\n")
        if (hasVpnInterface) {
            nSb.append("VPN IP: Resolving...\n")
            nSb.append("IP Proxy Leak: Resolving...\n")
        }

        sbNetwork = nSb.toString()


        // --- TAB 2: Apps ---
        val appSb = StringBuilder("🕵️ MAIN VPN APPS DISCOVERY\n━━━━━━━━━━━━\n")
        val targets = listOf(
            "com.v2ray.ang" to "v2rayNG",
            "com.github.shadowsocks" to "Shadowsocks",
            "net.openvpn.openvpn" to "OpenVPN Connect",
            "org.amnezia.vpn" to "Amnezia VPN",
            "com.happproxy" to "Happ VPN",
            "com.wireguard.android" to "WireGuard",
        )
        targets.forEach { (pkg, name) ->
            val installed = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
            appSb.append("$name: ${if (installed) "✅ Installed" else "❌ Not found"}\n")
        }
        sbApps = appSb.toString()

        // --- TAB 3: Raw Capabilities---
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNet)
        var rawCaps = caps?.toString() ?: "None"

        rawCaps = rawCaps.replace(" [", "\n\n[")
            .replace(" Transports: ", "\n🌐 TRANSPORTS:\n  • ")
            .replace(" Capabilities: ", "\n\n🛡️ CAPABILITIES:\n  • ")
            .replace(" TransportInfo: ", "\n\n⚙️ TRANSPORT INFO:\n  • ")
            .replace(" Specifier: ", "\n\nSpecifier:")
            .replace("MLO Information: ", "\nMLO Information:")

        val structuredCaps = rawCaps
            .replace(Regex("&|, "), "\n  • ")
            .replace("[", "")
            .replace("]", "")

        sbCap = "📡 RAW CAPABILITIES\n━━━━━━━━━━━━\n$structuredCaps"

        // --- TAB 4: Raw Network Info ---
        @Suppress("DEPRECATION")
        val info = cm.getNetworkInfo(activeNet)
        sbNet = "📜 RAW NETWORK INFO\n━━━━━━━━━━━━\n" + (info?.toString()?.replace(", ", ",\n") ?: "None")


        // --- ASYNC IP RESOLVING ---
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Run slow tasks in the background

            val portsResultDeferred = async { scanLocalPorts() }
            val directIpDeferred = async { getIpViaInterface() }
            val vpnIpDeferred = async { getIpViaTun() }
            val vpnProxyIpDeferred = async { getIpViaProxy() }

            val portsResult = portsResultDeferred.await()
            val directIp = directIpDeferred.await()
            val vpnIp = vpnIpDeferred.await()
            val vpnProxyIp = vpnProxyIpDeferred.await()

            var directIpCountry = "N/A"
            var vpnIpCountry = "N/A"
            var vpnProxyIpCountry = "N/A"

            var directIpCountryDeferred: Deferred<String>? = null
            var vpnIpCountryDeferred: Deferred<String>? = null
            var vpnProxyIpCountryDeferred: Deferred<String>? = null

            if (!directIp.startsWith("Err")) {
               directIpCountryDeferred = async { getCountryByIp(directIp) }
            }

            if (!vpnIp.startsWith("Err") && directIp != vpnIp && vpnIp != "Tunnel Not Detected") {
                vpnIpCountryDeferred = async { getCountryByIp(vpnIp) }
            }

            if (!vpnProxyIp.startsWith("Err")) {
                val regex = Regex("""Port (\d+) → (\d+\.\d+\.\d+\.\d+)""")
                val match = regex.find(vpnProxyIp)
                val prxPort = match?.groupValues?.get(1)?.toIntOrNull()
                val ipAddr = match?.groupValues?.get(2)
                if (prxPort != null && ipAddr != null) {
                    vpnProxyIpCountryDeferred = async { getCountryByIpViaProxy(ipAddr, prxPort) }
                }
            }

            directIpCountry =  directIpCountryDeferred?.await() ?: "N/A"
            vpnIpCountry =  vpnIpCountryDeferred?.await() ?: "N/A"
            vpnProxyIpCountry =  vpnProxyIpCountryDeferred?.await() ?: "N/A"


            withContext(Dispatchers.Main) {
                // 2. Re-build the Network Tab string (Tab 0)
                val finalSb = StringBuilder()

                // Add Interfaces and IPs
                finalSb.append(interfaceString)

                // Add VPN Interface
                finalSb.append(vpnIfaceString)

                // Add Capabilities
                finalSb.append(capsString)

                // Add VPN service check
                finalSb.append(vpnServiceString)

                // Add DNS servers
                finalSb.append(dnsString)

                // 2. Add the resolved IPs at the bottom
                finalSb.append("\n🌍 PUBLIC IP CHECK\n━━━━━━━━━━━━\n")
                if (!directIp.startsWith("Err:")) {
                    finalSb.append("Direct IP: $directIp ($directIpCountry)\n")
                } else {
                    finalSb.append("Direct IP: Unknown\n")
                }

                if (vpnIp == "Tunnel Not Detected") {
                    finalSb.append("VPN IP: $vpnIp\n")
                } else if (directIp == vpnIp && !vpnIp.startsWith("Err:")) {
                    finalSb.append("VPN IP: $vpnIp ($directIpCountry)(✅ VPN is Allowed)\n")
                } else if (directIp != vpnIp && !directIp.startsWith("Err:") && !vpnIp.startsWith("Err:")) {
                    finalSb.append("VPN IP: $vpnIp ($vpnIpCountry)(⚠️ LEAK DETECTED)\n")
                } else if (directIp != vpnProxyIp && !vpnProxyIp.startsWith("Err:") && vpnIp.startsWith("Err:")) {
                    finalSb.append("IP Proxy Leak: $vpnProxyIp ($vpnProxyIpCountry)(⚠️ LEAK DETECTED)\n")
                } else {
                    finalSb.append("VPN IP: Unknown\n")
                }

                // 3. Update the global variable and refresh UI
                sbNetwork = finalSb.toString()
                sbPorts = portsResult

                // 4. Force UI Refresh
                viewPager.adapter?.notifyDataSetChanged()

                btnCheck.isEnabled = true
                btnCheck.text = "Check"
                btnCheck.setTextColor("#29402D".toColorInt())
                btnCheck.setBackgroundColor("#6ceb80".toColorInt())
            }
        }
    }


    // Get Country by IP
    private fun getCountryByIp(ip: String): String {
        val url = java.net.URL("http://ip-api.com/json/$ip?fields=country")
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                return Regex("\"country\":\"(.*?)\"").find(json)?.groupValues?.get(1) ?: "Unknown"
            } else "HTTP Error: $responseCode"
        } catch (e: Exception) {
            "Err: ${e.localizedMessage}"
        } finally {
            connection?.disconnect()
        }
    }


    // Direct IP — uses default network, no binding
    private fun getIpViaInterface(): String {
        val url = java.net.URL("https://api.ipify.org")
        var connection: javax.net.ssl.HttpsURLConnection? = null
        return try {
            connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
            connection.apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val responseCode = connection.responseCode
            if (responseCode == 200) connection.inputStream.bufferedReader().use { it.readText() }.trim()
            else "HTTP Error: $responseCode"
        } catch (e: Exception) {
            "Err: ${e.localizedMessage}"
        } finally {
            connection?.disconnect()
        }
    }


    private fun getIpViaTun(): String {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val tun0Network = cm.allNetworks.find { network ->
                val iface = cm.getLinkProperties(network)?.interfaceName ?: ""
                iface.startsWith("tun") || iface.startsWith("ppp") || iface.startsWith("wg")
            } ?: return "Tunnel Not Detected"

            // Build an OkHttpClient bound to the tun0 network
            val boundClient = okhttp3.OkHttpClient.Builder()
                .socketFactory(tun0Network.socketFactory)
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("https://api.ipify.org")
                .header("Connection", "close")
                .build()

            boundClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string()?.trim() ?: "Empty"
                else "HTTP Error: ${response.code}"
            }
        } catch (e: Exception) {
            "Err: ${e.localizedMessage}"
        }
    }


    private fun getIpViaProxy(): String {
        val ports = listOf(1080, 5450, 8088, 8089, 10808, 10809, 50108)
        for (port in ports) {
            try {
                val proxy = java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", port)
                )

                val client = okhttp3.OkHttpClient.Builder()
                    .proxy(proxy)
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url("https://api.ipify.org")
                    .header("Connection", "close")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val ip = response.body?.string()?.trim()
                        if (!ip.isNullOrEmpty()) return "Port $port → $ip"
                    }
                }
            } catch (e: Exception) {
                // Try next port
            }
        }
        return "Err: No proxy responded on any port"
    }


    // Get Country by IP via Proxy
    private fun getCountryByIpViaProxy(ip: String, prxPort: Int): String {
        try {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", prxPort)
            )
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url("http://ip-api.com/json/$ip?fields=country")
                .header("Connection", "close")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return "Err: Empty body"
                    return Regex("\"country\":\"(.*?)\"").find(json)?.groupValues?.get(1)
                        ?: "Unknown"
                }
                return "Err: Request failed"
            }
        } catch (e: Exception) {
            // Try next port
        }
        return "Err: Server didn't respond"
    }


    private fun scanLocalPorts(): String {
        val sb = StringBuilder("🔍 LOCAL PORT SCAN\n━━━━━━━━━━━━━━━━━━━━\n")
        val commonPorts = listOf(80, 443, 1080, 5450, 8088, 8089, 10808, 10809, 50108)
        val targets = listOf("127.0.0.1", "localhost", "0.0.0.0")
        var anyOpen = false

        for (port in commonPorts) {
            for (target in targets) {
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(target, port), 250)
                    sb.append("  • Port $port: ✅ OPEN ($target)\n")
                    socket.close()
                    anyOpen = true
                    break
                } catch (e: Exception) { }
            }
        }
        if (!anyOpen) sb.append("  • No active listeners detected.\n")
        return sb.toString()
    }


    inner class TabAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 5

        override fun getItemId(position: Int): Long {
            val content = when(position) {
                0 -> sbNetwork
                1 -> sbApps
                2 -> sbCap
                3 -> sbNet
                else -> sbPorts
            }
            return (position.toString() + content).hashCode().toLong()
        }

        override fun containsItem(itemId: Long): Boolean {
            return (0 until itemCount).any { getItemId(it) == itemId }
        }

        override fun createFragment(position: Int): Fragment {
            return when(position) {
                0 -> ContentFragment.newInstance(sbNetwork)
                1 -> ContentFragment.newInstance(sbApps)
                2 -> ContentFragment.newInstance(sbCap)
                3 -> ContentFragment.newInstance(sbNet)
                else -> ContentFragment.newInstance(sbPorts)
            }
        }
    }
}
