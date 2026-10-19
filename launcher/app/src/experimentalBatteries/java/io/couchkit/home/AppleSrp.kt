// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * EXPERIMENTAL flavor only. SRP-6a client for the web sign-in used by [CloudBatteries], byte-compatible
 * with the `srp` Python library as used by pyicloud (rfc5054 + no_username_in_x, SHA-256, 2048-bit group).
 * Pure JVM; no network or Android dependency. Only used when the user opted in to `--store-password`.
 *
 * Password derivation ("s2k" / "s2k_fo"): p = SHA256(password); s2k_fo uses p's lowercase hex text;
 * key = PBKDF2-HMAC-SHA256(p, salt, iteration, 32).
 */
object AppleSrp {
    private val N = BigInteger(
        "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310D" +
        "CD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF74" +
        "7359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D5E" +
        "A77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB694B" +
        "5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16)
    private val G = BigInteger.valueOf(2)
    private const val WIDTH = 256

    class Client(private val accountName: String, password: String, a: BigInteger? = null) {
        private val a: BigInteger = a ?: BigInteger(1, ByteArray(256).also { SecureRandom().nextBytes(it) }).setBit(2047)
        private val bigA: BigInteger = G.modPow(this.a, N)
        private val passwordHash: ByteArray = sha256(password.toByteArray(Charsets.UTF_8))

        /** "a" sent to signin/init (long_to_bytes(A)). */
        val publicA: ByteArray get() = bytes(bigA)

        /** Computes M1 and M2 (H_AMK) from the signin/init reply; null if an SRP safety check fails. */
        fun process(salt: ByteArray, bBytes: ByteArray, iterations: Int, protocol: String): Pair<ByteArray, ByteArray>? {
            val bigB = BigInteger(1, bBytes)
            if (bigB.mod(N).signum() == 0) return null
            val u = BigInteger(1, h(pad(bigA), pad(bigB)))
            if (u.signum() == 0) return null
            val p = when (protocol) {
                "s2k" -> passwordHash
                "s2k_fo" -> hex(passwordHash).toByteArray(Charsets.US_ASCII)
                else -> throw IllegalArgumentException("unsupported SRP protocol: $protocol")
            }
            val key = pbkdf2(p, salt, iterations, 32)
            val x = BigInteger(1, h(salt, h(byteArrayOf(':'.code.toByte()), key)))
            val k = BigInteger(1, h(bytes(N), pad(G)))
            val v = G.modPow(x, N)
            val s = bigB.subtract(k.multiply(v)).mod(N).modPow(a.add(u.multiply(x)), N)
            val bigK = sha256(bytes(s))
            val hn = sha256(bytes(N)); val hg = sha256(pad(G))
            val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
            val m1 = h(xor, sha256(accountName.toByteArray(Charsets.UTF_8)), salt, bytes(bigA), bytes(bigB), bigK)
            val m2 = h(bytes(bigA), m1, bigK)
            return m1 to m2
        }
    }

    /** Python long_to_bytes: unsigned, no leading zeros. */
    private fun bytes(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    private fun pad(n: BigInteger): ByteArray {
        val b = bytes(n)
        return if (b.size >= WIDTH) b else ByteArray(WIDTH - b.size) + b
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun h(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** PBKDF2-HMAC-SHA256; the password is raw bytes, so PBEKeySpec (char[]) cannot be used. */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val out = ByteArray(length)
        var block = 1; var off = 0
        while (off < length) {
            mac.update(salt); mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(t.size, length - off)
            System.arraycopy(t, 0, out, off, n)
            off += n; block++
        }
        return out
    }
}
