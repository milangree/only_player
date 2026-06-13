package one.only.player.core.data.remote

import java.security.MessageDigest
import one.only.player.core.model.RemoteServer

private val hexChars = "0123456789abcdef".toCharArray()

internal fun RemoteServer.toAuthFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(username.toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    digest.update(password.toByteArray(Charsets.UTF_8))
    val bytes = digest.digest()
    val chars = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        chars[index * 2] = hexChars[value ushr 4]
        chars[index * 2 + 1] = hexChars[value and 0x0F]
    }
    return chars.concatToString()
}
