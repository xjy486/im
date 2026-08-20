package com.jitong.im.android.local

import com.jitong.im.android.security.AndroidSecretBox
import java.io.File

/**
 * Stores media as encrypted envelopes. The file contents are never the decoded image bytes.
 */
class EncryptedMediaCache(
    private val directory: File,
    accountMediaKeyAlias: String,
) {
    private val box = AndroidSecretBox(accountMediaKeyAlias)

    init {
        directory.mkdirs()
    }

    @Synchronized
    fun put(name: String, content: ByteArray): String {
        val file = file(name)
        file.parentFile?.mkdirs()
        file.writeText(box.encrypt(content), Charsets.UTF_8)
        return file.relativeTo(directory).path
    }

    @Synchronized
    fun get(name: String): ByteArray? {
        val file = file(name)
        if (!file.isFile) return null
        return runCatching { box.decrypt(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    @Synchronized
    fun getByPath(relativePath: String?): ByteArray? {
        if (relativePath.isNullOrBlank()) return null
        val candidate = File(directory, relativePath)
        if (!candidate.canonicalPath.startsWith(directory.canonicalPath + File.separator)) {
            return null
        }
        return runCatching { box.decrypt(candidate.readText(Charsets.UTF_8)) }.getOrNull()
    }

    @Synchronized
    fun delete(relativePath: String?) {
        if (relativePath.isNullOrBlank()) return
        val candidate = File(directory, relativePath)
        if (candidate.canonicalPath.startsWith(directory.canonicalPath + File.separator)) {
            candidate.delete()
        }
    }

    @Synchronized
    fun clear() {
        directory.deleteRecursively()
    }

    fun deleteKey() {
        box.deleteKey()
    }

    private fun file(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid media cache name" }
        return File(directory, "$name.enc")
    }
}
