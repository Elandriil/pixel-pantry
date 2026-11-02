package app.pixelpantry.tv.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test fun hostAndPortForms() {
        for (ok in listOf("host:1", "192.168.1.20:8765", "desktop.local:8765", "[fe80::1]:8765", "[::1]:1", "[fe80::1%wlan0]:8765")) {
            assertTrue(ok, ServerAddress.isValid(ok))
        }
    }

    @Test fun rejectsMissingPortsSchemesAndBareIpv6() {
        for (bad in listOf("", "host", ":8765", "http://host:8765", "host:0", "host:70000", "host:abc", "a b:1", "user@host:1",
            "[fe80::1]", "fe80::1:8765", "[fe80::1:8765", "[]:8765", "[fe80::1]x:8765")) {
            assertFalse(bad, ServerAddress.isValid(bad))
        }
    }
}
