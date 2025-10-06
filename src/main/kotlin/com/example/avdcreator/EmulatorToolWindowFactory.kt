package com.example.avdcreator

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.swing.*
import javax.swing.Timer
import kotlin.concurrent.thread

// -----------------------------
// Persistent State Service
// -----------------------------
@Service(Service.Level.APP)
@State(name = "ProxyStateService", storages = [Storage("ProxyState.xml")])
class ProxyStateService : PersistentStateComponent<ProxyStateService.State> {

    data class State(
        var deviceProxies: MutableMap<String, String> = mutableMapOf()
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        this.myState = state
    }

    fun setProxy(deviceId: String, proxy: String) {
        myState.deviceProxies[deviceId] = proxy
    }

    fun clearProxy(deviceId: String) {
        myState.deviceProxies.remove(deviceId)
    }

    fun getProxy(deviceId: String): String? = myState.deviceProxies[deviceId]
}

// -----------------------------
// Tool Window Factory
// -----------------------------
class EmulatorToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // ✅ Toggle for advanced settings
        val advancedToggle = JCheckBox("Show Advanced Options")
        panel.add(advancedToggle)

        // ------------------
        // Advanced UI group
        // ------------------
        val advancedPanel = JPanel()
        advancedPanel.layout = BoxLayout(advancedPanel, BoxLayout.Y_AXIS)

        val deviceList = JComboBox<String>()
//        val proxyField = JTextField(20)
        val proxyOptions = DefaultComboBoxModel<String>()
        proxyOptions.addElement("None")
        proxyOptions.addElement("Local Proxy (localhost:8080)")
        proxyOptions.addElement("Corporate Proxy (proxy.jpmchase.net:10443)")
        proxyOptions.addElement("Custom...")
        val proxyDropdown = JComboBox(proxyOptions)

        val setProxyButton = JButton("Set Proxy")
        val clearProxyButton = JButton("Clear Proxy")
        val refreshButton = JButton("Refresh Devices")
        val launchButton = JButton("Launch Selected AVD")
        val killSelectedButton = JButton("Kill Selected Emulator") // ✅ Added

        advancedPanel.add(JLabel("Available AVDs:"))
        advancedPanel.add(deviceList)
        advancedPanel.add(JLabel("Proxy (host:port):"))
        advancedPanel.add(proxyDropdown)
        advancedPanel.add(setProxyButton)
        advancedPanel.add(clearProxyButton)
        advancedPanel.add(launchButton)
        advancedPanel.add(refreshButton)
        advancedPanel.add(killSelectedButton) // ✅ Added

        // Initially hidden
        advancedPanel.isVisible = false

        // ------------------
        // Always visible UI
        // ------------------
        val dualLaunchButton = JButton("Launch Two Emulators (Local + JPMC)")
        val killAllButton = JButton("Kill All Emulators")

        val logArea = JTextArea(15, 60).apply { isEditable = false }
        val scrollPane = JScrollPane(logArea)

        panel.add(dualLaunchButton)
        panel.add(killAllButton)
        panel.add(scrollPane)
        panel.add(advancedPanel)

        // Toggle logic
        advancedToggle.addActionListener {
            advancedPanel.isVisible = advancedToggle.isSelected
            panel.revalidate()
            panel.repaint()
        }

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // -----------------------------
        // Actions
        // -----------------------------
        refreshButton.addActionListener {
            refreshDevices(deviceList, logArea)
        }

        launchButton.addActionListener {
            val selectedAvd = deviceList.selectedItem as? String ?: return@addActionListener
            launchAvd(selectedAvd, logArea)
            Timer(5000) { refreshDevices(deviceList, logArea) }.start()
        }

        setProxyButton.addActionListener {
            val selectedDevice = deviceList.selectedItem as? String ?: return@addActionListener
            val proxySelection = proxyDropdown.selectedItem as? String ?: return@addActionListener

            val proxy = when (proxySelection) {
                "None" -> null
                "Local Proxy (localhost:8080)" -> "localhost:8080"
                "Corporate Proxy (proxy.jpmchase.net:10443)" -> "proxy.jpmchase.net:10443"
                else -> proxySelection // custom entered
            }

            val runningDevices = getRunningDevices()
            runningDevices.forEach { deviceId ->
                val avdName = getAvdName(deviceId)
                if (selectedDevice.contains(avdName)) {
                    setProxyWithAdb(proxy, deviceId)
                    ApplicationManager.getApplication().getService(ProxyStateService::class.java)
                        .setProxy(deviceId, proxy ?: "")
                }
            }
            refreshDevices(deviceList, logArea)
        }
        proxyDropdown.addActionListener {
            val selected = proxyDropdown.selectedItem as String
            if (selected == "Custom...") {
                val custom = JOptionPane.showInputDialog(panel, "Enter custom proxy (host:port):")
                if (!custom.isNullOrBlank()) {
                    proxyOptions.insertElementAt(custom, proxyOptions.size - 1)
                    proxyDropdown.selectedItem = custom
                } else {
                    proxyDropdown.selectedIndex = 0 // fallback to None
                }
            }
        }

        clearProxyButton.addActionListener {
            val selectedDevice = deviceList.selectedItem as? String ?: return@addActionListener
            val runningDevices = getRunningDevices()
            runningDevices.forEach { deviceId ->
                val avdName = getAvdName(deviceId)
                if (selectedDevice.contains(avdName)) {
                    setProxyWithAdb(null, deviceId)
                    ApplicationManager.getApplication().getService(ProxyStateService::class.java)

                }
            }
            refreshDevices(deviceList, logArea)
        }

        dualLaunchButton.addActionListener {
            dualLaunch(logArea)
            Timer(8000) { refreshDevices(deviceList, logArea) }.start()
        }
        killSelectedButton.addActionListener {
            val selectedDevice = deviceList.selectedItem as? String ?: return@addActionListener
            val runningDevices = getRunningDevices()
            runningDevices.forEach { deviceId ->
                val avdName = getAvdName(deviceId)
                if (selectedDevice.contains(avdName)) {
                    killEmulator(deviceId, logArea)
                }
            }
            Timer(3000) { refreshDevices(deviceList, logArea) }.start()
        }

        killAllButton.addActionListener {
            killAllEmulators(logArea)
            Timer(4000) { refreshDevices(deviceList, logArea) }.start()
        }


        // initial load
        refreshDevices(deviceList, logArea)
    }

    private fun getSdkPath(): String? {
        val envSdk = System.getenv("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_HOME")

        val home = System.getProperty("user.home")
        val candidates = listOfNotNull(
            envSdk,
            "$home/Library/Android/sdk",        // macOS
            "$home/Android/Sdk",                // Linux
            "C:\\Users\\${System.getProperty("user.name")}\\AppData\\Local\\Android\\Sdk" // Windows
        )

        return candidates.map { File(it) }.firstOrNull { it.exists() }?.absolutePath
    }

    private fun killEmulator(deviceSerial: String, logArea: JTextArea) {
        val sdkPath = getSdkPath() ?: return
        val adbPath = "$sdkPath/platform-tools/adb"
        val avdName = getAvdName(deviceSerial)

        thread {
            try {
                SwingUtilities.invokeLater {
                    logArea.append("⏳ Killing emulator $avdName ($deviceSerial)...\n")
                }

                val process = ProcessBuilder(adbPath, "-s", deviceSerial, "emu", "kill")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()

                SwingUtilities.invokeLater {
                    logArea.append("✅ Emulator $avdName ($deviceSerial) killed successfully\n")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    logArea.append("❌ Failed to kill $avdName ($deviceSerial) → ${e.message}\n")
                }
            }
        }
    }

    private fun killAllEmulators(logArea: JTextArea) {
        val devices = getRunningDevices()
        if (devices.isEmpty()) {
            SwingUtilities.invokeLater {
                logArea.append("ℹ️ No running emulators to kill\n")
            }
            return
        }

        devices.forEach { deviceId ->
            killEmulator(deviceId, logArea)
        }
    }


    private fun getRunningDevices(): List<String> {
        val sdkPath = getSdkPath() ?: return emptyList()
        val adbPath = "$sdkPath/platform-tools/adb"
        return try {
            val process = ProcessBuilder(adbPath, "devices")
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val devices = reader.readLines()
                .drop(1)
                .mapNotNull { it.split("\t").firstOrNull() }
                .filter { it.isNotBlank() }
            process.waitFor()
            devices
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getAvdName(deviceSerial: String): String {
        val sdkPath = getSdkPath() ?: return "Unknown AVD"
        val adbPath = "$sdkPath/platform-tools/adb"
        return try {
            val process = ProcessBuilder(adbPath, "-s", deviceSerial, "emu", "avd", "name")
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val name = reader.readLine()?.trim()
            process.waitFor()
            name ?: "Unknown AVD"
        } catch (e: Exception) {
            "Unknown AVD"
        }
    }

    private fun getDeviceProxy(deviceSerial: String): String {
        val sdkPath = getSdkPath() ?: return "SDK not found"
        val adbPath = "$sdkPath/platform-tools/adb"
        return try {
            val process =
                ProcessBuilder(adbPath, "-s", deviceSerial, "shell", "settings", "get", "global", "http_proxy")
                    .redirectErrorStream(true)
                    .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val proxy = reader.readLine()?.trim()
            process.waitFor()
            if (proxy.isNullOrBlank() || proxy == ":0") "None" else proxy
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

//    private fun setProxyWithAdb(proxy: String?, deviceSerial: String) {
//        val sdkPath = getSdkPath() ?: return
//        val adbPath = "$sdkPath/platform-tools/adb"
//        thread {
//            try {
//                val args = if (proxy == null) {
//                    listOf(adbPath, "-s", deviceSerial, "shell", "settings", "put", "global", "http_proxy", ":0")
//                } else {
//                    listOf(adbPath, "-s", deviceSerial, "shell", "settings", "put", "global", "http_proxy", proxy)
//                }
//                ProcessBuilder(args).inheritIO().start().waitFor()
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
//        }
//    }
    private fun setProxyWithAdb(proxy: String?, deviceSerial: String) {
        val sdkPath = getSdkPath() ?: return
        val adbPath = "$sdkPath/platform-tools/adb"

        thread {
            try {
                when (proxy) {
                    // -------------------------
                    // LOCAL SERVER EMULATOR
                    // -------------------------
                    "localhost:8080" -> {
                        // Reverse local port 8080 → emulator 8080
                        ProcessBuilder(adbPath, "-s", deviceSerial, "reverse", "tcp:8080", "tcp:8080")
                            .inheritIO()
                            .start()
                            .waitFor()

                        // Disable proxy inside emulator (use direct local)
                        ProcessBuilder(adbPath, "-s", deviceSerial, "shell", "settings", "put", "global", "http_proxy", ":0")
                            .inheritIO()
                            .start()
                            .waitFor()
                    }

                    // -------------------------
                    // REAL SERVER EMULATOR
                    // -------------------------
                    "proxy.jpmchase.net:10443" -> {
                        // Reverse corporate port 10443 → emulator 10443
                        ProcessBuilder(adbPath, "-s", deviceSerial, "reverse", "tcp:10443", "tcp:10443")
                            .inheritIO()
                            .start()
                            .waitFor()

                        // Set both HTTP and HTTPS proxies
                        ProcessBuilder(adbPath, "-s", deviceSerial, "shell", "settings", "put", "global", "http_proxy", "proxy.jpmchase.net:10443")
                            .inheritIO()
                            .start()
                            .waitFor()

                        ProcessBuilder(adbPath, "-s", deviceSerial, "shell", "settings", "put", "global", "https_proxy", "proxy.jpmchase.net:10443")
                            .inheritIO()
                            .start()
                            .waitFor()
                    }

                    // -------------------------
                    // CLEAR PROXY
                    // -------------------------
                    null, "", ":0" -> {
                        ProcessBuilder(adbPath, "-s", deviceSerial, "shell", "settings", "put", "global", "http_proxy", ":0")
                            .inheritIO()
                            .start()
                            .waitFor()
                    }

                    // -------------------------
                    // CUSTOM / OTHER PROXIES
                    // -------------------------
                    else -> {
                        ProcessBuilder(adbPath, "-s", deviceSerial, "shell", "settings", "put", "global", "http_proxy", proxy)
                            .inheritIO()
                            .start()
                            .waitFor()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private fun getAvdList(): List<String> {
        val avdDir = File(System.getProperty("user.home"), ".android/avd")
        if (!avdDir.exists()) return emptyList()
        return avdDir.listFiles()
            ?.filter { it.name.endsWith(".avd") }
            ?.map { it.name.removeSuffix(".avd") }
            ?: emptyList()
    }


    private fun refreshDevices(deviceList: JComboBox<String>, logArea: JTextArea) {
        deviceList.removeAllItems()
        logArea.text = ""

        val state = ApplicationManager.getApplication().getService(ProxyStateService::class.java)
        val avds = getAvdList()
        if (avds.isEmpty()) {
            logArea.append("No AVDs found in ~/.android/avd\n")
        } else {
            logArea.append("Available AVDs:\n")
            avds.forEach { avd ->
                deviceList.addItem(avd)
                logArea.append(" - $avd\n")
            }
        }

        val devices = getRunningDevices()
        if (devices.isNotEmpty()) {
            logArea.append("\nRunning devices:\n")
            devices.forEach { device ->
                val name = getAvdName(device)
                val liveProxy = getDeviceProxy(device)
                logArea.append("Device $name ($device) → Proxy: $liveProxy\n")
            }
        }
    }

    private fun getEmulatorPath(): String? {
        val home = System.getProperty("user.home")
        val exeSuffix = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""

        val candidates = listOfNotNull(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME"),
            "$home/Library/Android/sdk",
            "$home/Android/Sdk",
            "C:\\Users\\${System.getProperty("user.name")}\\AppData\\Local\\Android\\Sdk"
        )

        candidates.forEach {
            println("🔍 Checking SDK path: $it")
        }

        return candidates
            .map { File("$it/emulator/emulator$exeSuffix") }
            .firstOrNull { it.exists() }
            ?.absolutePath
    }


    private fun launchAvd(avdName: String, logArea: JTextArea) {
        val emulatorPath = getEmulatorPath() ?: run {
            logArea.append("❌ Emulator binary not found in SDK\n")
            return
        }
        thread {
            try {
                ProcessBuilder(emulatorPath, "-avd", avdName)
                    .redirectErrorStream(true)
                    .start()
                logArea.append("🚀 Launching AVD: $avdName\n")
            } catch (e: Exception) {
                logArea.append("❌ Failed to launch $avdName → ${e.message}\n")
            }
        }
    }

    private fun dualLaunch(logArea: JTextArea) {
        val emulatorPath = getEmulatorPath() ?: run {
            logArea.append("❌ Emulator binary not found in SDK\n")
            return
        }
        val avds = getAvdList()
        if (avds.size < 2) {
            JOptionPane.showMessageDialog(null, "Need at least 2 AVDs", "Error", JOptionPane.ERROR_MESSAGE)
            return
        }

        val avd1 = avds[0]
        val avd2 = avds[1]

        thread {
            try {
                ProcessBuilder(emulatorPath, "-avd", avd1, "-http-proxy", "localhost:8080")
                    .inheritIO()
                    .start()
                logArea.append("🚀 Launched $avd1 with Local Proxy (localhost:8080)\n")

                ProcessBuilder(emulatorPath, "-avd", avd2, "-http-proxy", "proxy.jpmchase.net:10443")
                    .inheritIO()
                    .start()
                logArea.append("🚀 Launched $avd2 with Corporate Proxy (proxy.jpmchase.net:10443)\n")

            } catch (e: Exception) {
                e.printStackTrace()
                JOptionPane.showMessageDialog(
                    null,
                    "Failed dual launch: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

}

