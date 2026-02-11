package com.example.avdcreator.advanced

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.swing.*
import kotlin.concurrent.thread

class EmulatorToolWindowFactory3 : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        val mockButton = JButton("🚀 Launch Mock Emulator")
        val proxyButton = JButton("🌐 Launch Proxy Emulator")
        val advancedToggle = JCheckBox("Show Advanced Options")

        val logArea = JTextArea(14, 60).apply { isEditable = false }
        val scrollPane = JScrollPane(logArea)

        panel.add(mockButton)
        panel.add(Box.createVerticalStrut(4))
        panel.add(proxyButton)
        panel.add(Box.createVerticalStrut(8))
        panel.add(advancedToggle)
        panel.add(Box.createVerticalStrut(8))
        panel.add(scrollPane)

        // ---------------- Advanced panel ----------------
        val advPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        advPanel.isVisible = false

        val mockDropdown = JComboBox<String>()
        val proxyDropdown = JComboBox<String>()
        val customField = JTextField(20)
        val launchMockAdv = JButton("Launch Mock")
        val launchProxyAdv = JButton("Launch Proxy")
        val launchCustom = JButton("Launch Custom Proxy")
        val killDropdown = JComboBox<String>()
        val killButton = JButton("Kill Selected Emulator")

        advPanel.add(JLabel("Select Mock Emulator:"))
        advPanel.add(mockDropdown)
        advPanel.add(launchMockAdv)

        advPanel.add(JLabel("Select Proxy Emulator:"))
        advPanel.add(proxyDropdown)
        advPanel.add(launchProxyAdv)

        advPanel.add(JLabel("Custom Proxy (host:port):"))
        advPanel.add(customField)
        advPanel.add(launchCustom)

        advPanel.add(JLabel("Kill Emulator:"))
        advPanel.add(killDropdown)
        advPanel.add(killButton)

        panel.add(advPanel)

        advancedToggle.addActionListener {
            advPanel.isVisible = advancedToggle.isSelected
            panel.revalidate()
            panel.repaint()
        }

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // ---------------- Load AVDs ----------------
        fun refreshAvdLists() {
            val avds = getAvdList()
            mockDropdown.removeAllItems()
            proxyDropdown.removeAllItems()
            killDropdown.removeAllItems()
            avds.forEach {
                mockDropdown.addItem(it)
                proxyDropdown.addItem(it)
                killDropdown.addItem(it)
            }
        }
        refreshAvdLists()

        // ---------------- Actions ----------------

        mockButton.addActionListener { launchMockEmulator(logArea, null) }
        proxyButton.addActionListener { launchProxyEmulator(logArea, null) }

        launchMockAdv.addActionListener {
            val selected = mockDropdown.selectedItem as? String
            launchMockEmulator(logArea, selected)
        }

        launchProxyAdv.addActionListener {
            val selected = proxyDropdown.selectedItem as? String
            launchProxyEmulator(logArea, selected)
        }

        launchCustom.addActionListener {
            val proxy = customField.text.trim()
            if (proxy.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Enter a proxy host:port", "Error", JOptionPane.ERROR_MESSAGE)
                return@addActionListener
            }
            val selected = if (mockDropdown.selectedItem != null) mockDropdown.selectedItem as String else null
            launchCustomProxyEmulator(logArea, selected, proxy)
        }

        killButton.addActionListener {
            val avd = killDropdown.selectedItem as? String ?: return@addActionListener
            killEmulatorByAvd(avd, logArea)
        }
    }

    // ---------------- Emulator Launch Helpers ----------------

    private fun launchMockEmulator(logArea: JTextArea, avdName: String?) {
        val avds = getAvdList()
        val avd = avdName ?: avds.firstOrNull()
        if (avd == null) {
            logArea.append("❌ No Mock AVDs found\n")
            return
        }
        val emulatorPath = getEmulatorPath() ?: run { logArea.append("❌ Emulator not found\n"); return }
        val adb = getAdbPath() ?: run { logArea.append("❌ adb not found\n"); return }

        thread {
            try {
                logArea.append("[${avd}] 🚀 Launching Mock Emulator\n")
                val existing = getRunningDevices(adb).toSet()

                ProcessBuilder(emulatorPath, "-avd", avd)
                    .redirectErrorStream(true).start()

                val device = waitForNewEmulator(adb, existing)
                waitForBootCompleted(adb, device, logArea, avd)

                logArea.append("[${avd}] 🔁 Applying Mock Proxy (localhost:8080)\n")
                ProcessBuilder(adb, "-s", device, "reverse", "tcp:8080", "tcp:8080").start().waitFor()
                ProcessBuilder(adb, "-s", device, "shell", "settings", "put", "global", "http_proxy", ":0").start().waitFor()

                val finalProxy = readHttpProxy(adb, device)
                logArea.append("[${avd}] ✅ Ready with proxy: $finalProxy\n")
            } catch (e: Exception) {
                logArea.append("[${avd}] ❌ Launch failed: ${e.message}\n")
            }
        }
    }

    private fun launchProxyEmulator(logArea: JTextArea, avdName: String?) {
        val avds = getAvdList()
        val avd = avdName ?: avds.getOrNull(1)
        if (avd == null) {
            logArea.append("❌ No Proxy AVDs found\n")
            return
        }
        val emulatorPath = getEmulatorPath() ?: run { logArea.append("❌ Emulator not found\n"); return }
        val adb = getAdbPath() ?: run { logArea.append("❌ adb not found\n"); return }

        thread {
            try {
                logArea.append("[${avd}] 🌐 Launching Proxy Emulator\n")
                val existing = getRunningDevices(adb).toSet()
                ProcessBuilder(emulatorPath, "-avd", avd).redirectErrorStream(true).start()
                val device = waitForNewEmulator(adb, existing)
                waitForBootCompleted(adb, device, logArea, avd)

                logArea.append("[${avd}] 🔁 Applying Corporate Proxy (8443)\n")
                ProcessBuilder(adb, "-s", device, "reverse", "tcp:8443", "tcp:8443").start().waitFor()
                ProcessBuilder(adb, "-s", device, "shell", "settings", "put", "global", "http_proxy", "proxy.jpmchase.net:8443").start().waitFor()
                ProcessBuilder(adb, "-s", device, "shell", "settings", "put", "global", "https_proxy", "proxy.jpmchase.net:8443").start().waitFor()

                val finalProxy = readHttpProxy(adb, device)
                logArea.append("[${avd}] ✅ Ready with proxy: $finalProxy\n")
            } catch (e: Exception) {
                logArea.append("[${avd}] ❌ Proxy setup failed: ${e.message}\n")
            }
        }
    }

    private fun launchCustomProxyEmulator(logArea: JTextArea, avdName: String?, proxy: String) {
        val avds = getAvdList()
        val avd = avdName ?: avds.firstOrNull()
        if (avd == null) {
            logArea.append("❌ No AVDs found\n")
            return
        }
        val emulatorPath = getEmulatorPath() ?: run { logArea.append("❌ Emulator not found\n"); return }
        val adb = getAdbPath() ?: run { logArea.append("❌ adb not found\n"); return }

        thread {
            try {
                logArea.append("[${avd}] 🚀 Launching Custom Proxy Emulator\n")
                val existing = getRunningDevices(adb).toSet()
                ProcessBuilder(emulatorPath, "-avd", avd).redirectErrorStream(true).start()
                val device = waitForNewEmulator(adb, existing)
                waitForBootCompleted(adb, device, logArea, avd)

                logArea.append("[${avd}] 🔁 Applying Custom Proxy ($proxy)\n")
                ProcessBuilder(adb, "-s", device, "shell", "settings", "put", "global", "http_proxy", proxy).start().waitFor()
                val finalProxy = readHttpProxy(adb, device)
                logArea.append("[${avd}] ✅ Ready with proxy: $finalProxy\n")
            } catch (e: Exception) {
                logArea.append("[${avd}] ❌ Custom proxy failed: ${e.message}\n")
            }
        }
    }

    private fun killEmulatorByAvd(avd: String, logArea: JTextArea) {
        val adb = getAdbPath() ?: run { logArea.append("❌ adb not found\n"); return }
        val devices = getRunningDevices(adb)
        devices.forEach { device ->
            val name = getAvdName(adb, device)
            if (name == avd) {
                ProcessBuilder(adb, "-s", device, "emu", "kill").start().waitFor()
                logArea.append("[${avd}] 🛑 Killed\n")
            }
        }
    }

    // ---------------- Helper Functions ----------------

    private fun getRunningDevices(adb: String): List<String> {
        return try {
            val process = ProcessBuilder(adb, "devices").start()
            BufferedReader(InputStreamReader(process.inputStream))
                .readLines().drop(1)
                .mapNotNull { it.split("\t").firstOrNull() }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getAvdList(): List<String> {
        val dir = File(System.getProperty("user.home"), ".android/avd")
        return dir.listFiles()?.filter { it.name.endsWith(".avd") }?.map { it.name.removeSuffix(".avd") } ?: emptyList()
    }

    private fun getSdkPath(): String? {
        val home = System.getProperty("user.home")
        val candidates = listOfNotNull(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME"),
            "$home/Library/Android/sdk",
            "$home/Android/Sdk",
            "C:\\Users\\${System.getProperty("user.name")}\\AppData\\Local\\Android\\Sdk"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.absolutePath
    }

    private fun getEmulatorPath(): String? {
        val sdk = getSdkPath() ?: return null
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        val file = File("$sdk/emulator/emulator$exe")
        return if (file.exists()) file.absolutePath else null
    }

    private fun getAdbPath(): String? {
        val sdk = getSdkPath() ?: return null
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        val file = File("$sdk/platform-tools/adb$exe")
        return if (file.exists()) file.absolutePath else null
    }

    private fun waitForNewEmulator(adb: String, existing: Set<String>): String {
        repeat(90) {
            val new = getRunningDevices(adb).firstOrNull { it.startsWith("emulator-") && it !in existing }
            if (new != null) return new
            Thread.sleep(1000)
        }
        throw RuntimeException("Emulator never appeared")
    }

    private fun waitForBootCompleted(adb: String, device: String, logArea: JTextArea, avd: String) {
        logArea.append("[$avd] ⏳ Waiting for boot...\n")
        repeat(120) {
            val output = ProcessBuilder(adb, "-s", device, "shell", "getprop", "sys.boot_completed")
                .start().inputStream.bufferedReader().readLine()?.trim()
            if (output == "1") return
            Thread.sleep(1000)
        }
        throw RuntimeException("Boot not completed for $avd")
    }

    private fun getAvdName(adb: String, device: String): String {
        return try {
            val process = ProcessBuilder(adb, "-s", device, "emu", "avd", "name").start()
            process.inputStream.bufferedReader().readLine()?.trim() ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun readHttpProxy(adb: String, device: String): String {
        return try {
            ProcessBuilder(adb, "-s", device, "shell", "settings", "get", "global", "http_proxy")
                .start().inputStream.bufferedReader().readLine()?.trim() ?: "unknown"
        } catch (e: Exception) {
            "error"
        }
    }
}
