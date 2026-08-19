package com.jitong.im.desktop.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeychainTest {
    @Test
    fun in_memory_keychain_matches_the_mac_keychain_port_contract() {
        val keychain = InMemoryKeychain()
        keychain.write("service", "account", "secret")
        assertEquals("secret", keychain.read("service", "account"))

        keychain.delete("service", "account")
        assertNull(keychain.read("service", "account"))
    }
}
