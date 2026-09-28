package org.reventor.sync.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.reventor.sync.protocol.Hex
import org.reventor.sync.protocol.SyncIdentity
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Where per-user state lives, per platform convention. */
fun appConfigDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val base = when {
        os.contains("windows") -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"))
        os.contains("mac") || os.contains("darwin") ->
            File(System.getProperty("user.home"), "Library/Application Support")
        else -> File(
            System.getenv("XDG_CONFIG_HOME") ?: File(System.getProperty("user.home"), ".config").absolutePath
        )
    }
    return File(base, "reventor-sync").apply { mkdirs() }
}

@Serializable
data class DesktopConfig(
    val deviceId: String,
    val deviceName: String,
    /** Hex PKCS#8 private key. Stored unencrypted in the user profile (V1). */
    val identityPrivate: String,
    /** Hex X.509 public key. */
    val identityPublic: String,
    val syncEnabled: Boolean = true,
) {
    fun identity(): SyncIdentity = SyncIdentity.fromEncodings(
        deviceId, deviceName,
        Hex.decode(identityPrivate), Hex.decode(identityPublic),
    )

    companion object {
        private val json = Json { prettyPrint = true }
        private val file get() = File(appConfigDir(), "config.json")

        fun loadOrCreate(): DesktopConfig {
            val existing = file.takeIf { it.isFile }?.let {
                runCatching { json.decodeFromString<DesktopConfig>(it.readText()) }.getOrNull()
            }
            if (existing != null) return existing

            val hostName = runCatching { java.net.InetAddress.getLocalHost().hostName }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "Desktop"
            val created = DesktopConfig(
                deviceId = UUID.randomUUID().toString(),
                deviceName = hostName,
                identityPrivate = "",
                identityPublic = "",
            )
            // Generate the key pair eagerly so the file always holds a full identity.
            val identity = SyncIdentity.generate(created.deviceId, created.deviceName)
            val full = created.copy(
                identityPrivate = Hex.encode(identity.privateKeyBytes()),
                identityPublic = Hex.encode(identity.publicKeyBytes()),
            )
            save(full)
            return full
        }

        fun save(config: DesktopConfig) {
            val f = file
            try {
                Files.setPosixFilePermissions(
                    f.toPath(),
                    PosixFilePermissions.fromString("rw-------"),
                )
            } catch (_: Exception) {
                // Windows or non-POSIX FS: rely on profile-dir ACLs.
            }
            f.writeText(json.encodeToString(config))
        }
    }
}
