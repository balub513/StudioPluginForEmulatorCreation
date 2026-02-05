package com.example.avdcreator.n1

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.swing.*
import kotlin.concurrent.thread

class EmulatorToolWindowFactory2 : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        val mockButton = JButton("🚀 Launch Mock Emulator")
        val proxyButton = JButton("🌐 Launch Proxy Emulator")

        val logArea = JTextArea(14, 60).apply { isEditable = false }
        val scrollPane = JScrollPane(logArea)

        panel.add(mockButton)
        panel.add(Box.createVerticalStrut(8))
        panel.add(proxyButton)
        panel.add(Box.createVerticalStrut(12))
        panel.add(scrollPane)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        mockButton.addActionListener { launchMockEmulator(logArea) }
        proxyButton.addActionListener { launchProxyEmulator(logArea) }
    }

    // -------------------------------------------------------
    // MOCK EMULATOR
    // -------------------------------------------------------

    private fun launchMockEmulator(logArea: JTextArea) {
        val avds = getAvdList()
        if (avds.isEmpty()) {
            logArea.append("❌ No AVDs found\n")
            return
        }

        val avdName = avds[0]
        val emulatorPath = getEmulatorPath() ?: return
        val sdkPath = getSdkPath() ?: return
        val adbPath = "$sdkPath/platform-tools/adb"

        thread {
            try {
                val existingDevices = getRunningDevices().toSet()

                ProcessBuilder(emulatorPath, "-avd", avdName)
                    .redirectErrorStream(true)
                    .start()

                SwingUtilities.invokeLater {
                    logArea.append("🚀 Launching Mock Emulator: $avdName\n")
                }

                val deviceId = waitForNewEmulator(adbPath, existingDevices)

                SwingUtilities.invokeLater {
                    logArea.append("⏳ Waiting for boot completion...\n")
                }

                waitForBootCompleted(adbPath, deviceId)

                SwingUtilities.invokeLater {
                    logArea.append("🔁 Applying adb reverse (8080)...\n")
                }

                ProcessBuilder(adbPath, "-s", deviceId, "reverse", "tcp:8080", "tcp:8080")
                    .start()
                    .waitFor()

                ProcessBuilder(
                    adbPath, "-s", deviceId,
                    "shell", "settings", "put", "global", "http_proxy", ":0"
                )
                    .start()
                    .waitFor()

                SwingUtilities.invokeLater {
                    logArea.append("✅ Mock Emulator ready (localhost:8080)\n")
                }

            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    logArea.append("❌ Mock launch failed → ${e.message}\n")
                }
            }
        }
    }

    // -------------------------------------------------------
    // PROXY EMULATOR
    // -------------------------------------------------------

    private fun launchProxyEmulator(logArea: JTextArea) {
        val avds = getAvdList()
        if (avds.size < 2) {
            logArea.append("❌ Need at least 2 AVDs\n")
            return
        }

        val avdName = avds[1]
        val emulatorPath = getEmulatorPath() ?: return
        val sdkPath = getSdkPath() ?: return
        val adbPath = "$sdkPath/platform-tools/adb"

        thread {
            try {
                // 🔥 IMPORTANT: kill existing instance if running
                killEmulatorByAvd(adbPath, avdName, logArea)

                // 1️⃣ Launch emulator
                ProcessBuilder(emulatorPath, "-avd", avdName)
                    .redirectErrorStream(true)
                    .start()

                SwingUtilities.invokeLater {
                    logArea.append("🌐 Launching Proxy Emulator: $avdName\n")
                }

                // 2️⃣ Wait for this AVD
                val deviceId = waitForEmulatorByAvd(adbPath, avdName)

                SwingUtilities.invokeLater {
                    logArea.append("⏳ Waiting for proxy emulator boot...\n")
                }

                waitForBootCompleted(adbPath, deviceId)

                SwingUtilities.invokeLater {
                    logArea.append("🔁 Applying corporate proxy...\n")
                }

                ProcessBuilder(adbPath, "-s", deviceId, "reverse", "tcp:10443", "tcp:10443")
                    .start()
                    .waitFor()

                ProcessBuilder(
                    adbPath, "-s", deviceId,
                    "shell", "settings", "put", "global",
                    "http_proxy", "proxy.jpmchase.net:10443"
                )
                    .start()
                    .waitFor()

                ProcessBuilder(
                    adbPath, "-s", deviceId,
                    "shell", "settings", "put", "global",
                    "https_proxy", "proxy.jpmchase.net:10443"
                )
                    .start()
                    .waitFor()

                SwingUtilities.invokeLater {
                    logArea.append("✅ Proxy Emulator ready\n")
                }

            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    logArea.append("❌ Proxy setup failed → ${e.message}\n")
                }
            }
        }
    }
    private fun killEmulatorByAvd(adbPath: String, avdName: String, logArea: JTextArea) {
        val devices = getRunningDevices()

        for (device in devices) {
            val process = ProcessBuilder(
                adbPath, "-s", device,
                "emu", "avd", "name"
            )
                .redirectErrorStream(true)
                .start()

            val name = process.inputStream.bufferedReader().readLine()?.trim()
            process.waitFor()

            if (name == avdName) {
                SwingUtilities.invokeLater {
                    logArea.append("🛑 Killing running $avdName ($device)\n")
                }

                ProcessBuilder(adbPath, "-s", device, "emu", "kill")
                    .start()
                    .waitFor()

                Thread.sleep(3000) // give emulator time to die
            }
        }
    }



    private fun waitForEmulatorByAvd(adbPath: String, avdName: String): String {
        repeat(90) {
            val process = ProcessBuilder(adbPath, "devices")
                .redirectErrorStream(true)
                .start()

            val devices = process.inputStream.bufferedReader()
                .readLines()
                .drop(1)
                .mapNotNull { it.split("\t").firstOrNull() }
                .filter { it.startsWith("emulator-") }

            for (device in devices) {
                val nameProcess = ProcessBuilder(
                    adbPath, "-s", device,
                    "emu", "avd", "name"
                )
                    .redirectErrorStream(true)
                    .start()

                val name = nameProcess.inputStream.bufferedReader().readLine()?.trim()
                nameProcess.waitFor()

                if (name == avdName) return device
            }

            Thread.sleep(1000)
        }

        throw RuntimeException("Proxy emulator never appeared")
    }


    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private fun waitForNewEmulator(adbPath: String, existing: Set<String>): String {
        repeat(60) {
            val devices = getRunningDevices()
            val newDevice = devices.firstOrNull {
                it.startsWith("emulator-") && it !in existing
            }
            if (newDevice != null) return newDevice
            Thread.sleep(1000)
        }
        throw RuntimeException("Emulator never appeared")
    }

    private fun waitForBootCompleted(adbPath: String, deviceId: String) {
        repeat(90) {
            val process = ProcessBuilder(
                adbPath, "-s", deviceId,
                "shell", "getprop", "sys.boot_completed"
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readLine()?.trim()
            process.waitFor()

            if (output == "1") return
            Thread.sleep(1000)
        }
        throw RuntimeException("Boot did not complete")
    }

    private fun getRunningDevices(): List<String> {
        val sdkPath = getSdkPath() ?: return emptyList()
        val adbPath = "$sdkPath/platform-tools/adb"

        return try {
            val process = ProcessBuilder(adbPath, "devices")
                .redirectErrorStream(true)
                .start()

            BufferedReader(InputStreamReader(process.inputStream))
                .readLines()
                .drop(1)
                .mapNotNull { it.split("\t").firstOrNull() }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getAvdList(): List<String> {
        val avdDir = File(System.getProperty("user.home"), ".android/avd")
        return avdDir.listFiles()
            ?.filter { it.name.endsWith(".avd") }
            ?.map { it.name.removeSuffix(".avd") }
            ?: emptyList()
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
        val sdkPath = getSdkPath() ?: return null
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        val file = File("$sdkPath/emulator/emulator$exe")
        return if (file.exists()) file.absolutePath else null
    }
}
