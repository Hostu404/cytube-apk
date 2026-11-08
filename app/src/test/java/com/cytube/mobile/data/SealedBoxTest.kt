package com.cytube.mobile.data

import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SealedBox with an ordinary AES key: on the phone its key is kept in the
 * Android Keystore (see AuthStore), which unit tests don't have, but the
 * sealing itself is the same.
 */
class SealedBoxTest {

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val box = SealedBox { key }

    @Test
    fun `opens what it sealed`() {
        val cookie = "someone:1893456000000:salt:hash:1"
        assertEquals(cookie, box.open(box.seal(cookie, "auth_cookie"), "auth_cookie"))
    }

    @Test
    fun `seals the same text differently each time`() {
        assertNotEquals(box.seal("someone", "name"), box.seal("someone", "name"))
    }

    @Test
    fun `a value moved to another name doesn't open`() {
        assertNull(box.open(box.seal("someone", "name"), "auth_cookie"))
    }

    @Test
    fun `a value tampered with doesn't open`() {
        val bytes = Base64.getDecoder().decode(box.seal("someone", "name"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(box.open(Base64.getEncoder().encodeToString(bytes), "name"))
    }

    @Test
    fun `a value sealed under another key doesn't open`() {
        val other = SealedBox(::newKey)
        assertNull(box.open(other.seal("someone", "name"), "name"))
    }

    @Test
    fun `anything that isn't a sealed value reads as nothing`() {
        assertNull(box.open("", "name"))
        assertNull(box.open("not base64!", "name"))
        assertNull(box.open(Base64.getEncoder().encodeToString(byteArrayOf(12, 1, 2, 3)), "name"))
        assertNull(box.open(Base64.getEncoder().encodeToString(byteArrayOf(99) + ByteArray(40)), "name"))
    }
}
