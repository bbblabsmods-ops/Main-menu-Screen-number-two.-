package com.example.questlauncher

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val signingKey: PrivateKey
    private val signingCert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val pair = loadOrCreateKeys(context)
        signingKey = pair.first
        signingCert = pair.second
    }

    override fun getPrivateKey(): PrivateKey = signingKey

    override fun getCertificate(): Certificate = signingCert

    override fun getDeviceName(): String = "QuestLauncher"

    companion object {
        @Volatile
        private var instance: AdbManager? = null

        fun get(context: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }

        private fun loadOrCreateKeys(context: Context): Pair<PrivateKey, Certificate> {
            val keyFile = File(context.filesDir, "adb_key.pk8")
            val certFile = File(context.filesDir, "adb_cert.der")

            if (keyFile.exists() && certFile.exists()) {
                try {
                    val key = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
                    return Pair(key, cert)
                } catch (e: Exception) {
                    // make a fresh key pair below
                }
            }

            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val keyPair = generator.generateKeyPair()

            val name = X500Name("CN=QuestLauncher")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now),
                Date(now - 86_400_000L),
                Date(now + 3650L * 86_400_000L),
                name,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val cert: Certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

            keyFile.writeBytes(keyPair.private.encoded)
            certFile.writeBytes(cert.encoded)
            return Pair(keyPair.private, cert)
        }
    }
}

/** Every call blocks (with a time limit), so call it off the main thread. */
class AdbController(private val context: Context) {

    private val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)

    private fun manager(): AdbManager = AdbManager.get(context)

    private fun <T> limited(ms: Long, block: () -> T): T {
        val task = FutureTask(Callable { block() })
        Thread(task, "adb-op").apply { isDaemon = true; start() }
        try {
            return task.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            runCatching { manager().disconnect() }
            throw IllegalStateException("Timed out. Is Wireless debugging on?")
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiAddress(): String? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) null
        else "${ip and 255}.${(ip shr 8) and 255}.${(ip shr 16) and 255}.${(ip shr 24) and 255}"
    }.getOrNull()

    private fun hostsToTry(host: String): List<String> =
        listOfNotNull(host, wifiAddress(), "127.0.0.1").distinct()

    fun autoConnectEnabled(): Boolean = prefs.getBoolean("auto_connect", true)

    fun setAutoConnectEnabled(on: Boolean) {
        prefs.edit().putBoolean("auto_connect", on).apply()
    }

    fun pair(host: String, port: Int, code: String): Result<Unit> = runCatching {
        var ok = false
        for (h in hostsToTry(host)) {
            ok = runCatching { limited(15_000) { manager().pair(h, port, code) } }.getOrDefault(false)
            if (ok) break
        }
        if (!ok) error("Pairing was refused. Check the port and code, and keep the pairing window open.")
    }

    /** port == null: try the saved port, then find it automatically. */
    fun connect(host: String, port: Int?): Result<Unit> = runCatching {
        val m = manager()
        if (m.isConnected()) return@runCatching
        var ok = false
        if (port != null) {
            for (h in hostsToTry(host)) {
                ok = runCatching { limited(8_000) { m.connect(h, port) } }.getOrDefault(false)
                if (ok) {
                    prefs.edit().putString("last_host", h).putInt("last_port", port).apply()
                    break
                }
            }
        } else {
            val savedPort = prefs.getInt("last_port", 0)
            val savedHost = prefs.getString("last_host", null)
            if (savedPort > 0 && savedHost != null) {
                ok = runCatching { limited(5_000) { m.connect(savedHost, savedPort) } }.getOrDefault(false)
            }
            if (!ok) {
                ok = runCatching { limited(14_000) { m.autoConnect(context, 9_000L) } }.getOrDefault(false)
            }
        }
        if (!ok && !m.isConnected()) {
            error("Couldn't connect. Check that Wireless debugging is on, then try again.")
        }
    }

    fun isConnected(): Boolean =
        runCatching { limited(3_000) { manager().isConnected() } }.getOrDefault(false)

    fun disconnect() {
        runCatching { manager().disconnect() }
    }

    fun shell(command: String): Result<String> = runCatching {
        limited(8_000) {
            val stream = manager().openStream("shell:$command")
            stream.openInputStream().bufferedReader().use { it.readText() }
        }
    }
}
