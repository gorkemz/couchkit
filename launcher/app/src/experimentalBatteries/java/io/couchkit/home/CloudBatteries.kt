// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * EXPERIMENTAL, unofficial: device batteries from the iCloud web "Find" service. Only compiled into the
 * `experimentalBatteries` flavor. It talks to an undocumented web API, may break at any time and may
 * violate the provider's terms; see extras/experimental-batteries-bridge/README.md. Not affiliated with
 * or endorsed by Apple.
 *
 * Sign-in + 2FA happen once on a computer with the bridge (login.py). The bridge pushes a hand-off file
 * (files/icloud_session.json) with the session token, trust token and web cookies. On the next fetch the
 * file is moved into app-private storage (noBackupFilesDir/icloud.json) and the external copy is deleted
 * immediately, whether or not it could be parsed.
 *
 * Default mode is session-token only: no password is stored on the TV. When the web session expires the
 * status becomes "login_required", the pill hides, and the user re-runs the bridge. Only if the bridge was
 * run with the explicit opt-in `--store-password` does the hand-off contain the password, RSA-OAEP
 * encrypted to a non-exportable Android Keystore key; then the TV can renew the session itself via SRP
 * (at most every 30 min; never again after a rejection or a 2FA prompt). A hand-off without a password
 * removes any previously stored one. A secret-free status summary is written to files/batteries_status.json.
 */
object CloudBatteries : BatterySource {
    override val refreshMs = 10 * 60_000L
    private const val MAX_AGE_MS = 3 * 3_600_000L

    private const val IMPORT = "icloud_session.json"
    private const val STATE = "icloud.json"
    private const val STATUS = "batteries_status.json"

    private const val PUBKEY = "bridge_pub.der"
    private const val SRP_MIN_INTERVAL_MS = 30 * 60_000L

    private const val HOME = "https://www.icloud.com"
    private const val SETUP = "https://setup.icloud.com/setup/ws/1"
    private const val IDMSA = "https://idmsa.apple.com"
    private const val AUTH = "$IDMSA/appleauth/auth"
    private const val WIDGET_KEY = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
    /**
     * ISOLATED on purpose: the web endpoints only answer requests that look like the desktop web client
     * the session was created with (the bridge uses the same identity via pyicloud). This is a browser
     * user-agent string, i.e. client impersonation; it is one of the reasons this flavor is experimental
     * and never shipped. Used only by [Session.request] and the auth headers below.
     */
    private const val WEB_CLIENT_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
    private const val BUILD = "clientBuildNumber=2534Project66&clientMasteringNumber=2534B22"
    private val AUTH_CODES = setOf(401, 403, 409, 421, 450)
    private val KIND_ORDER = listOf("phone", "watch", "earbuds", "tablet", "laptop", "other")

    private class LoginRequired(msg: String) : Exception(msg)

    /** Last good list; empty if older than 3 h or signed out. */
    override fun cached(ctx: Context): List<BatterySource.Device> {
        val p = ctx.getSharedPreferences("batteries", 0)
        if (p.getString("status", null) != "ok") return emptyList()
        if (System.currentTimeMillis() - p.getLong("at", 0) > MAX_AGE_MS) return emptyList()
        return try {
            fromJson(JSONArray(p.getString("json", "[]")))
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun isStale(ctx: Context) =
        System.currentTimeMillis() - ctx.getSharedPreferences("batteries", 0).getLong("at", 0) > refreshMs

    override fun hasPendingImport(ctx: Context) = ctx.getExternalFilesDir(null)?.let { File(it, IMPORT).isFile } == true

    /**
     * Network call on a background thread. List on success; empty list when there is no session or it
     * expired (pill hides); null on a transient error (the current list stays, within the 3 h limit).
     */
    @Synchronized
    override fun fetch(ctx: Context): List<BatterySource.Device>? {
        val p = ctx.getSharedPreferences("batteries", 0)
        val now = System.currentTimeMillis()
        Vault.exportPublicKey(ctx)
        return try {
            val s = Session.load(ctx) ?: throw LoginRequired("no session (run the bridge)")
            val list = query(s)
            p.edit().putString("status", "ok").putLong("at", now).putString("json", toJson(list).toString()).apply()
            writeStatus(ctx, "ok", list.size, null, diag)
            list
        } catch (e: LoginRequired) {
            Log.w(TAG, "batteries: login_required: ${e.message}")
            p.edit().putString("status", "login_required").putLong("at", now).remove("json").apply()
            writeStatus(ctx, "login_required", 0, e.message)
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "batteries: ${e.javaClass.simpleName}: ${e.message}")
            writeStatus(ctx, "error", 0, "${e.javaClass.simpleName}: ${e.message}", diag)
            null
        }
    }

    /** Secret-free summary of the last query (HTTP codes, device class:level:status) for the status file. */
    private var diag = ""

    private fun query(s: Session): List<BatterySource.Device> {
        diag = ""
        if (s.findme.isEmpty() || s.dsid.isEmpty()) { accountLogin(s); diag += "login " }
        var r = initClient(s)
        diag += "init=${r.code}"
        if (r.code in AUTH_CODES) {
            // First a password-less renewal; only with a stored (opt-in) password try SRP
            val tokenOk = try { accountLogin(s); true } catch (e: LoginRequired) { diag += " token=${e.message}"; false }
            if (tokenOk) { r = initClient(s); diag += " retry=${r.code}" }
            if (!tokenOk || r.code in AUTH_CODES) {
                srpLogin(s)
                accountLogin(s)
                r = initClient(s)
                diag += " srp retry=${r.code}"
            }
        }
        if (r.code in AUTH_CODES) throw LoginRequired("find HTTP ${r.code}")
        if (r.code != 200) throw IOException("find HTTP ${r.code}")
        val j = JSONObject(r.body)
        val content = j.optJSONArray("content")
        if (content == null) {
            diag += " keys=" + j.keys().asSequence().take(8).joinToString(",") + " sc=" + j.optString("statusCode")
            throw IOException("no content in response")
        }
        val out = ArrayList<BatterySource.Device>()
        val seen = ArrayList<String>()
        for (i in 0 until content.length()) {
            val o = content.optJSONObject(i) ?: continue
            val status = o.optString("batteryStatus", "Unknown")
            val level = o.optDouble("batteryLevel", 0.0).let { if (it.isNaN()) 0.0 else it }
            seen.add("${o.optString("deviceClass")}:${"%.2f".format(Locale.ROOT, level)}:$status")
            if (level <= 0.0 && status == "Unknown") continue // no battery info (e.g. desktops)
            out.add(BatterySource.Device(
                o.optString("name").ifEmpty { o.optString("deviceDisplayName", "Device") },
                kindOf(o), level.coerceIn(0.0, 1.0).toFloat(),
                status == "Charging" || status == "Charged",
            ))
        }
        diag += " dev=[" + seen.joinToString(" ") + "]"
        // Devices sometimes come back without battery info: treat as transient, keep the current list
        if (out.isEmpty() && content.length() > 0) throw IOException("devices without battery info")
        return out.sortedBy { KIND_ORDER.indexOf(it.kind) }
    }

    private fun initClient(s: Session): Resp {
        val ctxJ = JSONObject()
            .put("appName", "iCloud Find (Web)").put("appVersion", "2.0").put("apiVersion", "3.0")
            .put("deviceListVersion", 1).put("fmly", s.withFamily)
            .put("timezone", "US/Pacific").put("inactiveTime", 0)
        return s.post("${s.findme}/fmipservice/client/web/initClient?${s.params()}",
            JSONObject().put("clientContext", ctxJ).toString())
    }

    /** Renews the web session with the stored session + trust tokens (no password). */
    private fun accountLogin(s: Session) {
        val body = JSONObject()
            .put("accountCountryCode", s.country).put("dsWebAuthToken", s.sessionToken)
            .put("extended_login", true).put("trustToken", s.trustToken)
        val r = s.post("$SETUP/accountLogin?${s.params()}", body.toString())
        if (r.code != 200) throw if (r.code in AUTH_CODES) LoginRequired("accountLogin HTTP ${r.code}")
            else IOException("accountLogin HTTP ${r.code}")
        val j = JSONObject(r.body)
        if (!j.optBoolean("hsaTrustedBrowser", false)) throw LoginRequired("session not trusted")
        j.optJSONObject("dsInfo")?.opt("dsid")?.let { s.dsid = it.toString() }
        j.optJSONObject("webservices")?.optJSONObject("findme")?.optString("url")
            ?.takeIf { it.isNotEmpty() }?.let { s.findme = it }
        s.save()
    }

    /**
     * SRP sign-in (same requests as pyicloud). Only possible when the user opted in to storing an
     * encrypted password with `--store-password`. On success the new session token is stored; call
     * accountLogin afterwards.
     */
    private fun srpLogin(s: Session) {
        if (s.passwordEnc.isEmpty()) throw LoginRequired("session expired; session-token-only mode (re-run the bridge)")
        if (s.srpFail.isNotEmpty()) throw LoginRequired("SRP rejected before (${s.srpFail}); re-run the bridge")
        val now = System.currentTimeMillis()
        if (now - s.srpAt < SRP_MIN_INTERVAL_MS) throw IOException("SRP tried within 30 min, waiting")
        s.srpAt = now; s.save()
        val password = try { Vault.decrypt(s.passwordEnc) } catch (e: Exception) {
            s.srpFail = "decrypt failed"; s.save(); throw LoginRequired("stored password cannot be decrypted (re-run the bridge)")
        }
        s.request("GET", "$AUTH/authorize/signin?frame_id=${s.enc(s.clientId)}&skVersion=7&iframeid=${s.enc(s.clientId)}" +
            "&client_id=$WIDGET_KEY&response_type=code&redirect_uri=${s.enc(HOME)}&response_mode=web_message" +
            "&state=${s.enc(s.clientId)}&authVersion=latest", null, emptyMap())
        val client = AppleSrp.Client(s.accountName, password)
        val initBody = JSONObject().put("a", b64(client.publicA)).put("accountName", s.accountName)
            .put("protocols", JSONArray().put("s2k").put("s2k_fo"))
        val ri = s.request("POST", "$AUTH/signin/init", initBody.toString(), authHeaders(s))
        diag += " srpinit=${ri.code}"
        if (ri.code == 401 || ri.code == 403) { s.srpFail = "init ${ri.code}"; s.save(); throw LoginRequired("SRP init HTTP ${ri.code}") }
        if (ri.code != 200) throw IOException("SRP init HTTP ${ri.code}")
        val ij = JSONObject(ri.body)
        val (m1, m2) = client.process(Base64.decode(ij.getString("salt"), Base64.DEFAULT),
            Base64.decode(ij.getString("b"), Base64.DEFAULT), ij.getInt("iteration"), ij.getString("protocol"))
            ?: throw IOException("SRP safety check failed")
        val done = JSONObject().put("accountName", s.accountName).put("c", ij.get("c"))
            .put("m1", b64(m1)).put("m2", b64(m2)).put("rememberMe", true)
            .put("trustTokens", JSONArray().apply { if (s.trustToken.isNotEmpty()) put(s.trustToken) })
        val rc = s.request("POST", "$AUTH/signin/complete?isRememberMeEnabled=true", done.toString(), authHeaders(s))
        diag += " srp=${rc.code}"
        when (rc.code) {
            200, 204 -> { s.srpFail = ""; s.save() }
            409 -> { s.srpFail = "2FA requested"; s.save(); throw LoginRequired("2FA requested (re-run the bridge)") }
            401, 403 -> { s.srpFail = "password rejected ${rc.code}"; s.save(); throw LoginRequired("password rejected (re-run the bridge)") }
            else -> throw IOException("SRP complete HTTP ${rc.code}")
        }
    }

    private fun authHeaders(s: Session): Map<String, String> = LinkedHashMap<String, String>().apply {
        put("Accept", "application/json, text/javascript")
        put("Content-Type", "application/json")
        put("Referer", IDMSA)
        put("X-Apple-OAuth-Client-Id", WIDGET_KEY)
        put("X-Apple-OAuth-Client-Type", "firstPartyAuth")
        put("X-Apple-OAuth-Redirect-URI", HOME)
        put("X-Apple-OAuth-Require-Grant-Code", "true")
        put("X-Apple-OAuth-Response-Mode", "web_message")
        put("X-Apple-OAuth-Response-Type", "code")
        put("X-Apple-OAuth-State", s.clientId)
        put("X-Apple-Frame-Id", s.clientId)
        put("X-Apple-Widget-Key", WIDGET_KEY)
        put("X-Apple-FD-Client-Info", JSONObject().put("U", WEB_CLIENT_UA).put("L", "en-US").put("Z", "GMT+00:00")
            .put("V", "1.1").put("F", "").toString())
        if (s.scnt.isNotEmpty()) put("scnt", s.scnt)
        if (s.sessionId.isNotEmpty()) put("X-Apple-ID-Session-Id", s.sessionId)
        if (s.authAttributes.isNotEmpty()) put("X-Apple-Auth-Attributes", s.authAttributes)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /**
     * Android Keystore RSA key for the optional stored password: the public key is written to files/ so
     * the bridge can encrypt to it; the private key never leaves the Keystore.
     */
    private object Vault {
        private const val ALIAS = "batteries-password"

        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun ensureKey(): KeyStore {
            val ks = keyStore()
            if (!ks.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                        .build())
                }.generateKeyPair()
            }
            return ks
        }

        fun exportPublicKey(ctx: Context) {
            try {
                val f = File(ctx.getExternalFilesDir(null) ?: return, PUBKEY)
                if (f.isFile && f.length() > 0) return
                f.writeBytes(ensureKey().getCertificate(ALIAS).publicKey.encoded)
            } catch (e: Exception) {
                Log.w(TAG, "batteries: cannot write public key: ${e.message}")
            }
        }

        /** RSA-OAEP: SHA-256 digest, MGF1 SHA-1 (the combination every Keystore supports). */
        fun decrypt(b64: String): String {
            val key = ensureKey().getKey(ALIAS, null) as PrivateKey
            val c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            c.init(Cipher.DECRYPT_MODE, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
            return c.doFinal(Base64.decode(b64, Base64.DEFAULT)).toString(Charsets.UTF_8)
        }
    }

    /** Maps the service's device class to a generic glyph kind. */
    private fun kindOf(o: JSONObject): String {
        val s = (o.optString("deviceClass") + " " + o.optString("deviceDisplayName")).lowercase(Locale.ROOT)
        return when {
            "airpods" in s || "beats" in s || "accessory" in s -> "earbuds"
            "watch" in s -> "watch"
            "ipad" in s -> "tablet"
            "iphone" in s || "ipod" in s -> "phone"
            "mac" in s -> "laptop"
            else -> "other"
        }
    }

    private fun toJson(list: List<BatterySource.Device>) = JSONArray().apply {
        for (d in list) put(JSONObject().put("name", d.name).put("kind", d.kind)
            .put("level", d.level.toDouble()).put("charging", d.charging))
    }

    private fun fromJson(a: JSONArray) = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        BatterySource.Device(o.getString("name"), o.getString("kind"), o.getDouble("level").toFloat(), o.getBoolean("charging"))
    }

    private fun writeStatus(ctx: Context, status: String, count: Int, error: String?, diag: String = "") {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: return
            val j = JSONObject().put("updated", System.currentTimeMillis()).put("status", status).put("count", count)
            if (error != null) j.put("error", error)
            if (diag.isNotEmpty()) j.put("diag", diag)
            File(dir, STATUS).writeText(j.toString())
        } catch (_: Exception) {
        }
    }

    private class Resp(val code: Int, val body: String)

    private class Cookie(val name: String, val value: String, val domain: String, val path: String,
                         val expires: Long, val secure: Boolean) {
        fun matches(host: String, path: String, now: Long): Boolean {
            if (expires in 1..now) return false
            val d = domain.removePrefix(".")
            return (host == d || host.endsWith(".$d")) && path.startsWith(this.path)
        }
    }

    /** Web session: tokens + cookie jar, persisted as JSON in app-private, non-backed-up storage. */
    private class Session(private val file: File, private val j: JSONObject) {
        val clientId: String = j.optString("client_id")
        var sessionToken: String = j.optString("session_token")
        var trustToken: String = j.optString("trust_token")
        var country: String = j.optString("account_country")
        var dsid: String = j.optString("dsid")
        var findme: String = j.optString("findme_url")
        val withFamily = j.optBoolean("with_family", false)
        val accountName: String = j.optString("apple_id")
        val passwordEnc: String = j.optString("password_enc")
        var scnt: String = j.optString("scnt")
        var sessionId: String = j.optString("session_id")
        var authAttributes: String = j.optString("auth_attributes")
        var srpAt: Long = j.optLong("srp_at", 0)
        var srpFail: String = j.optString("srp_fail")
        private val cookies = ArrayList<Cookie>()

        init {
            val a = j.optJSONArray("cookies") ?: JSONArray()
            for (i in 0 until a.length()) {
                val c = a.getJSONObject(i)
                cookies.add(Cookie(c.getString("name"), c.optString("value"), c.getString("domain"),
                    c.optString("path", "/"), c.optLong("expires", 0), c.optBoolean("secure")))
            }
        }

        fun params(): String {
            val sb = StringBuilder(BUILD).append("&clientId=").append(enc(clientId))
            if (dsid.isNotEmpty()) sb.append("&dsid=").append(enc(dsid))
            return sb.toString()
        }

        fun post(url: String, body: String) = request("POST", url, body, emptyMap())

        fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

        /** Like a pyicloud session: default headers + cookies; response cookies and session headers are kept. */
        fun request(method: String, url: String, body: String?, headers: Map<String, String>): Resp {
            val u = URL(url)
            val now = System.currentTimeMillis()
            val c = u.openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15_000; c.readTimeout = 20_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", WEB_CLIENT_UA)
                c.setRequestProperty("Origin", HOME)
                c.setRequestProperty("Referer", "$HOME/")
                c.setRequestProperty("Accept", "*/*")
                if (body != null) c.setRequestProperty("Content-Type", "application/json")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                val path = u.path.ifEmpty { "/" }
                val ck = cookies.filter { it.matches(u.host, path, now) }.joinToString("; ") { "${it.name}=${it.value}" }
                if (ck.isNotEmpty()) c.setRequestProperty("Cookie", ck)
                if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
                val code = c.responseCode
                for ((k, values) in c.headerFields) {
                    val v = values.firstOrNull()
                    when {
                        k == null -> {}
                        k.equals("Set-Cookie", true) -> values.forEach { store(u.host, it, now) }
                        v.isNullOrEmpty() -> {}
                        k.equals("X-Apple-Session-Token", true) -> sessionToken = v
                        k.equals("X-Apple-TwoSV-Trust-Token", true) -> trustToken = v
                        k.equals("X-Apple-ID-Account-Country", true) -> country = v
                        k.equals("scnt", true) -> scnt = v
                        k.equals("X-Apple-ID-Session-Id", true) -> sessionId = v
                        k.equals("X-Apple-Auth-Attributes", true) -> authAttributes = v
                    }
                }
                val text = (if (code < 400) c.inputStream else c.errorStream)
                    ?.use { it.readBytes().decodeToString() } ?: ""
                save()
                return Resp(code, text)
            } finally {
                c.disconnect()
            }
        }

        private fun store(host: String, header: String, now: Long) {
            val parts = header.split(';')
            val eq = parts[0].indexOf('=')
            if (eq <= 0) return
            val name = parts[0].substring(0, eq).trim()
            val value = parts[0].substring(eq + 1).trim()
            var domain = host; var path = "/"; var expires = 0L; var secure = false; var maxAge: Long? = null
            for (a in parts.drop(1)) {
                val i = a.indexOf('=')
                val k = (if (i < 0) a else a.substring(0, i)).trim().lowercase(Locale.ROOT)
                val v = if (i < 0) "" else a.substring(i + 1).trim()
                when (k) {
                    "domain" -> if (v.isNotEmpty()) domain = if (v.startsWith(".")) v else ".$v"
                    "path" -> if (v.startsWith("/")) path = v
                    "expires" -> expires = parseDate(v)
                    "max-age" -> maxAge = v.toLongOrNull()
                    "secure" -> secure = true
                }
            }
            maxAge?.let { expires = if (it <= 0) 1 else now + it * 1000 }
            val bare = domain.removePrefix(".")
            cookies.removeAll { it.name == name && it.path == path && it.domain.removePrefix(".") == bare }
            if (expires == 0L || expires > now) cookies.add(Cookie(name, value, domain, path, expires, secure))
        }

        fun save() {
            j.put("session_token", sessionToken).put("trust_token", trustToken).put("account_country", country)
                .put("dsid", dsid).put("findme_url", findme).put("scnt", scnt).put("session_id", sessionId)
                .put("auth_attributes", authAttributes).put("srp_at", srpAt).put("srp_fail", srpFail)
            j.put("cookies", JSONArray().apply {
                for (c in cookies) put(JSONObject().put("name", c.name).put("value", c.value).put("domain", c.domain)
                    .put("path", c.path).put("expires", c.expires).put("secure", c.secure))
            })
            val tmp = File(file.parentFile, "$STATE.tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(file)) throw IOException("cannot save session")
        }

        companion object {
            /**
             * Imports a new hand-off file if present (the external copy is ALWAYS deleted right away,
             * even if it is malformed), then loads the private copy.
             */
            fun load(ctx: Context): Session? {
                val file = File(ctx.noBackupFilesDir, STATE)
                ctx.getExternalFilesDir(null)?.let { File(it, IMPORT) }?.takeIf { it.isFile }?.let { ext ->
                    try {
                        val j = JSONObject(ext.readText())
                        if (j.optString("session_token").isNotEmpty()) {
                            // No password in the hand-off (default) = session-token-only: do not keep an
                            // older stored password either. A fresh session also clears the SRP block.
                            if (j.optString("password_enc").isEmpty()) j.remove("password_enc")
                            j.put("srp_fail", "").put("srp_at", 0)
                            Session(file, j).save()
                            Log.i(TAG, "batteries: imported new session (stored password: ${j.has("password_enc")})")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "batteries: hand-off file unreadable: ${e.message}")
                    } finally {
                        ext.delete()
                    }
                }
                if (!file.isFile) return null
                val j = JSONObject(file.readText())
                if (j.optString("session_token").isEmpty()) return null
                return Session(file, j)
            }

            private val DATE_FORMATS = listOf("EEE, dd-MMM-yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd-MMM-yy HH:mm:ss zzz")

            private fun parseDate(v: String): Long {
                for (f in DATE_FORMATS) {
                    try {
                        val df = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                        return df.parse(v)?.time ?: continue
                    } catch (_: Exception) {
                    }
                }
                return 0L
            }
        }
    }
}
