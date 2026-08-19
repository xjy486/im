package com.jitong.im.desktop.local

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

interface Keychain {
    fun read(service: String, account: String): String?
    fun write(service: String, account: String, value: String)
    fun delete(service: String, account: String)
}

/** macOS Keychain adapter. It never writes secrets to a local fallback file. */
class MacOsKeychain(
    private val commandRunner: (List<String>, String?) -> CommandResult = ::runCommand,
) : Keychain {
    override fun read(service: String, account: String): String? {
        val result = commandRunner(
            listOf("security", "find-generic-password", "-s", service, "-a", account, "-w"),
            null)
        return when {
            result.exitCode == 0 -> result.stdout.trimEnd('\n')
            result.exitCode == 44 -> null
            else -> throw KeychainException("Could not read Keychain item", result)
        }
    }

    override fun write(service: String, account: String, value: String) {
        val result = commandRunner(
            listOf(
                "security", "add-generic-password", "-U",
                "-s", service,
                "-a", account,
                "-w", value),
            null)
        if (result.exitCode != 0) {
            throw KeychainException("Could not write Keychain item", result)
        }
    }

    override fun delete(service: String, account: String) {
        val result = commandRunner(
            listOf("security", "delete-generic-password", "-s", service, "-a", account),
            null)
        if (result.exitCode != 0 && result.exitCode != 44) {
            throw KeychainException("Could not delete Keychain item", result)
        }
    }
}

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

class KeychainException(message: String, val result: CommandResult) : IllegalStateException(message)

private fun runCommand(command: List<String>, stdin: String?): CommandResult {
    val process = ProcessBuilder(command)
        .redirectErrorStream(false)
        .start()
    stdin?.let {
        process.outputStream.use { output ->
            output.write(it.toByteArray(StandardCharsets.UTF_8))
        }
    }
    val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    val stderr = process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    return CommandResult(process.waitFor(), stdout, stderr)
}

class InMemoryKeychain : Keychain {
    private val values = linkedMapOf<Pair<String, String>, String>()

    override fun read(service: String, account: String): String? = values[service to account]

    override fun write(service: String, account: String, value: String) {
        values[service to account] = value
    }

    override fun delete(service: String, account: String) {
        values.remove(service to account)
    }
}
