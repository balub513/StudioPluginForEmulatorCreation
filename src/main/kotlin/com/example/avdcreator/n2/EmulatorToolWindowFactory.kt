package com.example.avdcreator.n2

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.Dimension
import java.io.File
import javax.swing.*
import kotlin.concurrent.thread

class EmulatorToolWindowFactory4 : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        /* =======================
           MODE RADIO BUTTONS
        ======================== */
        val directRadio = JRadioButton("Direct launch", true)
        val advancedRadio = JRadioButton("Advance launch")

        ButtonGroup().apply {
            add(directRadio)
            add(advancedRadio)
        }

        val modePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(directRadio)
            add(Box.createHorizontalStrut(20))
            add(advancedRadio)
        }

        root.add(modePanel)
        root.add(Box.createVerticalStrut(10))

        /* =======================
           DIRECT PANEL
        ======================== */
        val directPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

        val directMockBtn = JButton("Launch Mock Emulator")
        val directProxyBtn = JButton("Launch Proxy Emulator")

        directPanel.add(directMockBtn)
        directPanel.add(Box.createHorizontalStrut(12))
        directPanel.add(directProxyBtn)

        root.add(directPanel)

        /* =======================
           ADVANCED PANEL
        ======================== */
        val advPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = false
        }

        val mockDropdown = JComboBox<String>()
        val proxyDropdown = JComboBox<String>()
        val customDropdown = JComboBox<String>()
        val customField = JTextField(10).apply {
            maximumSize = Dimension(300, preferredSize.height)
        }
        val advMockBtn = JButton("Launch")
        val advProxyBtn = JButton("Launch")
        val customBtn = JButton("Launch")

        advPanel.add(row("Mock Emulator", mockDropdown, advMockBtn))
        advPanel.add(Box.createVerticalStrut(8))
        advPanel.add(row("Proxy Emulator", proxyDropdown, advProxyBtn))
        advPanel.add(Box.createVerticalStrut(8))
        advPanel.add(row("Custom Proxy", customDropdown, customField, customBtn))

        root.add(advPanel)

        /* =======================
           LOG AREA
        ======================== */
        val logArea = JTextArea(14, 70).apply { isEditable = false }
        root.add(Box.createVerticalStrut(10))
        root.add(JScrollPane(logArea))

        /* =======================
           MODE TOGGLE
        ======================== */
        fun refreshDropdowns() {
            val notRunning = getAvdList().minus(getRunningAvdNames())
            listOf(mockDropdown, proxyDropdown, customDropdown).forEach {
                it.removeAllItems()
                notRunning.forEach { avd -> it.addItem(avd) }
            }
        }

        directRadio.addActionListener {
            directPanel.isVisible = true
            advPanel.isVisible = false
        }

        advancedRadio.addActionListener {
            directPanel.isVisible = false
            advPanel.isVisible = true
            refreshDropdowns()
        }

        /* =======================
           ACTIONS
        ======================== */
        directMockBtn.addActionListener {
            launchMock(logArea, null)
        }

        directProxyBtn.addActionListener {
            launchProxy(logArea, null)
        }

        advMockBtn.addActionListener {
            launchMock(logArea, mockDropdown.selectedItem as? String)
        }

        advProxyBtn.addActionListener {
            launchProxy(logArea, proxyDropdown.selectedItem as? String)
        }

        customBtn.addActionListener {
            val avd = customDropdown.selectedItem as? String ?: return@addActionListener
            val proxy = customField.text.trim()
            if (proxy.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Enter proxy host:port")
                return@addActionListener
            }
            launchCustom(logArea, avd, proxy)
        }

        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(root, "", false)
        )
    }

    /* =======================
       LAUNCH LOGIC
    ======================== */

    private fun launchMock(log: JTextArea, avd: String?) {
        launch(
            log, avd, "Mock",
            reversePort = "8080",
            httpProxy = ":0",
            httpsProxy = null
        )
    }

    private fun launchProxy(log: JTextArea, avd: String?) {
        launch(
            log, avd, "Proxy",
            reversePort = "10443",
            httpProxy = "proxy.jpmchase.net:10443",
            httpsProxy = "proxy.jpmchase.net:10443"
        )
    }

    private fun launchCustom(log: JTextArea, avd: String, proxy: String) {
        launch(
            log, avd, "Custom",
            reversePort = proxy.split(":")[1],
            httpProxy = proxy,
            httpsProxy = proxy
        )
    }
    /*
          For local server emulator below two commands should execute

           ./adb reverse tcp:8080 tcp:8080
           ./adb shell settings put global http_proxy:0

          For real  server emulator below two commands should execute

           ./adb reverse tcp:10443 tcp:10443
           ./adb shell settings put global http_proxy proxy.jpmchase.net:10443
           ./adb shell settings put global https_proxy proxy.jpmchase.net:10443
     */

    private fun launch(
        log: JTextArea,
        avd: String?,
        label: String,
        reversePort: String,
        httpProxy: String?,
        httpsProxy: String?
    ) {
        val emulator = getEmulatorPath() ?: return
        val adb = getAdbPath() ?: return
        val targetAvd = avd ?: getAvdList().firstOrNull() ?: return

        thread {
            log.append("[$label] Launching $targetAvd\n")
            ProcessBuilder(emulator, "-avd", targetAvd).start()

            val device = waitForDevice(adb)
            log.append("[$label] Boot completed\n")

            ProcessBuilder(adb, "-s", device, "reverse", "tcp:$reversePort", "tcp:$reversePort").start().waitFor()
            httpProxy?.let {
                ProcessBuilder(adb, "-s", device, "shell", "settings", "put", "global", "http_proxy", it).start().waitFor()
            }
            httpsProxy?.let {
                ProcessBuilder(adb, "-s", device, "shell", "settings", "put", "global", "https_proxy", it).start().waitFor()
            }

            val finalProxy = ProcessBuilder(adb, "-s", device, "shell", "settings", "get", "global", "http_proxy")
                .start().inputStream.bufferedReader().readLine()

            finalProxy?.let {
                log.append("[$label] READY → Proxy = $finalProxy\n")
            }
        }
    }

    /* =======================
       HELPERS
    ======================== */

    private fun row(label: String, vararg comps: JComponent) =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel(label))
            add(Box.createHorizontalStrut(10))
            comps.forEach {
                add(it)
                add(Box.createHorizontalStrut(6))
            }
        }

    private fun getAvdList() =
        File(System.getProperty("user.home"), ".android/avd")
            .listFiles()?.filter { it.name.endsWith(".avd") }
            ?.map { it.name.removeSuffix(".avd") } ?: emptyList()

    private fun getRunningAvdNames(): Set<String> {
        val adb = getAdbPath() ?: return emptySet()
        return ProcessBuilder(adb, "devices").start().inputStream.bufferedReader()
            .readLines().drop(1).mapNotNull {
                val d = it.split("\t").firstOrNull() ?: return@mapNotNull null
                ProcessBuilder(adb, "-s", d, "emu", "avd", "name")
                    .start().inputStream.bufferedReader().readLine()
            }.toSet()
    }

    private fun waitForDevice(adb: String): String {
        repeat(90) {
            val device = ProcessBuilder(adb, "devices").start().inputStream.bufferedReader()
                .readLines().drop(1).firstOrNull { it.startsWith("emulator-") }
                ?.split("\t")?.first()
            if (device != null) return device
            Thread.sleep(1000)
        }
        error("Emulator not detected")
    }

    private fun getSdk(): String =
        listOfNotNull(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME"),
            "${System.getProperty("user.home")}/Library/Android/sdk",
            "${System.getProperty("user.home")}/Android/Sdk"
        ).first { File(it).exists() }

    private fun getEmulatorPath() = File("${getSdk()}/emulator/emulator").absolutePath
    private fun getAdbPath() = File("${getSdk()}/platform-tools/adb").absolutePath
}
