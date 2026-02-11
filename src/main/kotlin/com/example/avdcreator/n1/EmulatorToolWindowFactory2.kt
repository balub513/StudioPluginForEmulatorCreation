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

    private val mockAvdName = "Pixel_Mock_Latest"
    private val proxyAvdName = "Pixel_Proxy_Latest"

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
        panel.add(Box.createVerticalStrut(8))

        // Button to clear the log output in the tool window
        val clearLogsButton = JButton("Clear Logs")
        panel.add(clearLogsButton)
        panel.add(Box.createVerticalStrut(8))
        panel.add(scrollPane)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        mockButton.addActionListener { launchMockEmulator(logArea) }
        proxyButton.addActionListener { launchProxyEmulator(logArea) }

        clearLogsButton.addActionListener {
            logArea.text = ""
        }
    }

    // -------------------------------------------------------
    // MOCK EMULATOR
    // -------------------------------------------------------

    private fun launchMockEmulator(logArea: JTextArea) {
        val emulatorPath = getEmulatorPath() ?: run {
            logArea.append("❌ Emulator binary not found in SDK\n")
            return
        }
        val sdkPath = getSdkPath() ?: run {
            logArea.append("❌ Android SDK not found\n")
            return
        }
        val adbPath = "$sdkPath/platform-tools/adb"

        thread {
            try {
                val (avdName, _) = ensurePixelAvds(sdkPath, logArea) ?: return@thread
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
        val emulatorPath = getEmulatorPath() ?: run {
            logArea.append("❌ Emulator binary not found in SDK\n")
            return
        }
        val sdkPath = getSdkPath() ?: run {
            logArea.append("❌ Android SDK not found\n")
            return
        }
        val adbPath = "$sdkPath/platform-tools/adb"

        thread {
            try {
                val (_, avdName) = ensurePixelAvds(sdkPath, logArea) ?: return@thread
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

                ProcessBuilder(adbPath, "-s", deviceId, "reverse", "tcp:8443", "tcp:8443")
                    .start()
                    .waitFor()

                ProcessBuilder(
                    adbPath, "-s", deviceId,
                    "shell", "settings", "put", "global",
                    "http_proxy", "proxy.jpmchase.net:8443"
                )
                    .start()
                    .waitFor()

                ProcessBuilder(
                    adbPath, "-s", deviceId,
                    "shell", "settings", "put", "global",
                    "https_proxy", "proxy.jpmchase.net:8443"
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

    /**
     * Ensure we have two Pixel AVDs on the latest installed Android SDK image.
     * Returns Pair(mockAvdName, proxyAvdName) or null on failure.
     */
    private fun ensurePixelAvds(sdkPath: String, logArea: JTextArea): Pair<String, String>? {
        val existing = getAvdList().toSet()
        if (existing.contains(mockAvdName) && existing.contains(proxyAvdName)) {
            return mockAvdName to proxyAvdName
        }

        val avdManager = findAvdManager(sdkPath)
        if (avdManager == null) {
            SwingUtilities.invokeLater {
                logArea.append("❌ Could not find avdmanager in SDK. Please install Android SDK Command-line Tools.\n")
            }
            return null
        }

        val systemImagePackage = findLatestSystemImagePackage(sdkPath, logArea) ?: return null
        val deviceId = findPixelDeviceId(avdManager, logArea) ?: return null

        listOf(mockAvdName, proxyAvdName).forEach { name ->
            if (!existing.contains(name)) {
                try {
                    SwingUtilities.invokeLater {
                        logArea.append("🆕 Creating AVD '$name' (device=$deviceId, image=$systemImagePackage)...\n")
                    }
                    val process = ProcessBuilder(
                        avdManager,
                        "create", "avd",
                        "-n", name,
                        "-k", systemImagePackage,
                        "-d", deviceId,
                        "--force"
                    )
                        .redirectErrorStream(true)
                        .start()

                    val output = process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()

                    SwingUtilities.invokeLater {
                        if (exitCode == 0) {
                            logArea.append("✅ Created AVD '$name'\n")
                        } else {
                            logArea.append("❌ Failed to create AVD '$name' (exit=$exitCode)\n$output\n")
                        }
                    }

                    if (exitCode != 0) return null
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        logArea.append("❌ Error while creating AVD '$name' → ${e.message}\n")
                    }
                    return null
                }
            }
        }

        return mockAvdName to proxyAvdName
    }

    private fun findAvdManager(sdkPath: String): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val exe = if (isWindows) ".bat" else ""

        // Common modern locations
        val directCandidates = listOf(
            File("$sdkPath/cmdline-tools/latest/bin/avdmanager$exe"),
            File("$sdkPath/cmdline-tools/bin/avdmanager$exe"),
            File("$sdkPath/tools/bin/avdmanager$exe")
        )

        val directMatch = directCandidates.firstOrNull { it.exists() }
        if (directMatch != null) return directMatch.absolutePath

        // Fallback: scan versioned cmdline-tools directories, e.g. cmdline-tools/10.0/bin/avdmanager
        val cmdlineDir = File("$sdkPath/cmdline-tools")
        if (cmdlineDir.exists()) {
            val versionDirs = cmdlineDir.listFiles()
                ?.filter { it.isDirectory && it.name !in setOf("bin", "latest") }
                .orEmpty()

            val scanned = versionDirs
                .map { File(it, "bin/avdmanager$exe") }
                .firstOrNull { it.exists() }

            if (scanned != null) return scanned.absolutePath
        }

        return null
    }

    /**
     * Finds the highest android-XX system image installed and builds its package id,
     * preferring google_apis_playstore, then google_apis, then any.
     */
    private fun findLatestSystemImagePackage(sdkPath: String, logArea: JTextArea): String? {
        val root = File("$sdkPath/system-images")
        if (!root.exists()) {
            SwingUtilities.invokeLater {
                logArea.append("❌ No system-images directory found in SDK. Please install at least one Android system image.\n")
            }
            return null
        }

        val androidDirs = root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("android-") }
            ?: emptyList()

        if (androidDirs.isEmpty()) {
            SwingUtilities.invokeLater {
                logArea.append("❌ No Android system images found. Please install them via SDK Manager.\n")
            }
            return null
        }

        val latestDir = androidDirs.maxByOrNull {
            it.name.removePrefix("android-").toIntOrNull() ?: 0
        }!!

        val typeDirs = latestDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (typeDirs.isEmpty()) {
            SwingUtilities.invokeLater {
                logArea.append("❌ No system image variants under ${latestDir.name}\n")
            }
            return null
        }

        val preferredOrder = listOf("google_apis_playstore", "google_apis", "aosp_atd")
        val typeDir = preferredOrder
            .mapNotNull { pref -> typeDirs.find { it.name.equals(pref, ignoreCase = true) } }
            .firstOrNull()
            ?: typeDirs.first()

        val abiDirs = typeDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (abiDirs.isEmpty()) {
            SwingUtilities.invokeLater {
                logArea.append("❌ No ABI folders under ${typeDir.path}\n")
            }
            return null
        }

        val abiDir = abiDirs.first()
        val pkg = "system-images;${latestDir.name};${typeDir.name};${abiDir.name}"

        SwingUtilities.invokeLater {
            logArea.append("ℹ️ Using system image package: $pkg\n")
        }

        return pkg
    }

    /**
     * Try to pick the latest Pixel device id from avdmanager; fall back to first device.
     */
    private fun findPixelDeviceId(avdManagerPath: String, logArea: JTextArea): String? {
        return try {
            val process = ProcessBuilder(avdManagerPath, "list", "device")
                .redirectErrorStream(true)
                .start()
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()

            var currentId: String? = null
            var candidates = mutableListOf<String>()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("id:")) {
                    // Example: id: 0 or "pixel_8"
                    val parts = trimmed.split("\"")
                    currentId = if (parts.size >= 2) parts[1] else null
                } else if (trimmed.startsWith("Name:")) {
                    val name = trimmed.removePrefix("Name:").trim()
                    if (name.contains("Pixel", ignoreCase = true) && currentId != null) {
                        candidates.add(currentId!!)
                    }
                }
            }

            val chosen = if (candidates.isNotEmpty()) {
                candidates.last()
            } else {
                // Fall back to first device id
                val firstId = lines.firstOrNull { it.trim().startsWith("id:") }?.let { line ->
                    val parts = line.trim().split("\"")
                    if (parts.size >= 2) parts[1] else null
                }
                firstId
            }

            if (chosen == null) {
                SwingUtilities.invokeLater {
                    logArea.append("❌ No devices found from avdmanager list device\n")
                }
            } else {
                SwingUtilities.invokeLater {
                    logArea.append("ℹ️ Using device id: $chosen\n")
                }
            }

            chosen
        } catch (e: Exception) {
            SwingUtilities.invokeLater {
                logArea.append("❌ Failed to query avdmanager devices → ${e.message}\n")
            }
            null
        }
    }

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
