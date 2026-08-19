package com.jitong.im.desktop.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacOsKeychainTest {
    @Test
    fun security_cli_adapter_maps_read_write_and_delete_commands() {
        val commands = mutableListOf<List<String>>()
        val keychain = MacOsKeychain { command, _ ->
            commands += command
            when {
                command[1] == "find-generic-password" -> CommandResult(0, "secret\n", "")
                else -> CommandResult(0, "", "")
            }
        }

        assertEquals("secret", keychain.read("service", "account"))
        keychain.write("service", "account", "new-secret")
        keychain.delete("service", "account")

        assertEquals(
            listOf(
                listOf("security", "find-generic-password", "-s", "service", "-a", "account", "-w"),
                listOf(
                    "security", "add-generic-password", "-U",
                    "-s", "service", "-a", "account", "-w", "new-secret"),
                listOf("security", "delete-generic-password", "-s", "service", "-a", "account")),
            commands)
    }

    @Test
    fun missing_security_item_is_reported_as_null() {
        val keychain = MacOsKeychain { _, _ -> CommandResult(44, "", "not found") }

        assertNull(keychain.read("service", "account"))
    }
}
