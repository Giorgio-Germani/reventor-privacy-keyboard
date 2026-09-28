package org.reventor.sync.desktop

import java.io.File

/** Best-effort launch-at-login for the three desktop platforms. */
object AutoStart {
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    fun currentCommand(): String =
        ProcessHandle.current().info().command().orElse("java")

    fun isEnabled(): Boolean = try {
        if (isWindows) {
            exec("reg", "query", KEY, "/v", NAME)
        } else if (isMac) {
            File(System.getProperty("user.home"), PLIST).isFile
        } else {
            File(System.getProperty("user.home"), LINUX_DESKTOP).isFile
        }
    } catch (e: Exception) {
        false
    }

    fun setEnabled(enabled: Boolean) {
        if (isWindows) {
            if (enabled) {
                exec("reg", "add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", currentCommand(), "/f")
            } else {
                exec("reg", "delete", KEY, "/v", NAME, "/f")
            }
        } else if (isMac) {
            val plist = File(System.getProperty("user.home"), PLIST)
            if (enabled) {
                plist.parentFile.mkdirs()
                plist.writeText(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0"><dict>
                        <key>Label</key><string>org.reventor.sync</string>
                        <key>ProgramArguments</key><array>
                            <string>${currentCommand()}</string>
                        </array>
                        <key>RunAtLoad</key><true/>
                    </dict></plist>
                    """.trimIndent()
                )
            } else {
                plist.delete()
            }
        } else {
            val desktop = File(System.getProperty("user.home"), LINUX_DESKTOP)
            if (enabled) {
                desktop.parentFile.mkdirs()
                desktop.writeText(
                    """
                    [Desktop Entry]
                    Type=Application
                    Name=Reventor Clipboard Sync
                    Exec=${currentCommand()}
                    X-GNOME-Autostart-enabled=true
                    """.trimIndent()
                )
            } else {
                desktop.delete()
            }
        }
    }

    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "ReventorSync"
    private const val PLIST = "Library/LaunchAgents/org.reventor.sync.plist"
    private const val LINUX_DESKTOP = ".config/autostart/reventor-sync.desktop"

    private fun exec(vararg command: String): Boolean = try {
        ProcessBuilder(*command).start().waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
