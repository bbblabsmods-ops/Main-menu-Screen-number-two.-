package com.example.questlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
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

/**
 * Talks to this headset's own Wireless debugging. The key pair is created once and kept
 * in the app's private files, so you only have to pair a single time.
 */
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

        /** Throws away the current connection object (used after a time-out). */
        fun reset() {
            val old: AdbManager?
            synchronized(this) {
                old = instance
                instance = null
            }
            if (old != null) {
                Thread {
                    try {
                        old.close()
                    } catch (e: Exception) {
                        // Nothing to do.
                    }
                }.start()
            }
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
                    // Fall through and make a fresh key pair.
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

/**
 * Small wrapper the rest of the app uses. Every call blocks, so call it off the main thread.
 * Every step has a time limit so it can never hang.
 */
class AdbController(private val context: Context) {

    private val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)

    var autoConnectEnabled: Boolean
        get() = prefs.getBoolean("auto_connect", true)
        set(value) {
            prefs.edit().putBoolean("auto_connect", value).apply()
        }

    private fun manager(): AdbManager = AdbManager.get(context)

    /** Runs a blocking call on its own thread and gives up after [timeoutMs]. */
    private fun <T> runWithTimeout(timeoutMs: Long, what: String, block: () -> T): T {
        val task = FutureTask<T>(Callable { block() })
        val thread = Thread(task, "adb-worker")
        thread.isDaemon = true
        thread.start()
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            AdbManager.reset()
            throw IllegalStateException("$what took too long and was stopped.")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun wifiAddress(): String? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork)
        props?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun hostsToTry(host: String): List<String> {
        val wifi = wifiAddress()
        return if (wifi != null && wifi != host) listOf(host, wifi) else listOf(host)
    }

    fun pair(host: String, port: Int, code: String): Result<Unit> {
        var last: Throwable = IllegalStateException("Pairing failed.")
        for (h in hostsToTry(host)) {
            val r = runCatching {
                runWithTimeout(15_000L, "Pairing") {
                    val ok = manager().pair(h, port, code)
                    if (!ok) {
                        error("Pairing was refused. Check the port and code, and keep the pairing window open.")
                    }
                    Unit
                }
            }
            if (r.isSuccess) return r
            last = r.exceptionOrNull() ?: last
        }
        return Result.failure(last)
    }

    /** Connects to a known port. Remembers it when it works. */
    fun connectManual(host: String, port: Int, timeoutMs: Long): Result<Unit> {
        var last: Throwable = IllegalStateException("Couldn't connect.")
        for (h in hostsToTry(host)) {
            val r = runCatching {
                runWithTimeout(timeoutMs, "Connecting") {
                    val m = manager()
                    val ok = m.connect(h, port)
                    if (!ok && !m.isConnected()) {
                        error("Couldn't connect to $h:$port. Check the port and that Wireless debugging is on.")
                    }
                    Unit
                }
            }
            if (r.isSuccess) {
                prefs.edit().putString("last_host", h).putInt("last_port", port).apply()
                return r
            }
            last = r.exceptionOrNull() ?: last
        }
        return Result.failure(last)
    }

    /** Finds Wireless debugging by itself, using the network. */
    fun connectFind(): Result<Unit> = runCatching {
        runWithTimeout(14_000L, "Looking for Wireless debugging") {
            val m = manager()
            val ok = m.autoConnect(context, 9_000L)
            if (!ok && !m.isConnected()) {
                error("Wireless debugging wasn't found. Turn it on and make sure you've paired once.")
            }
            Unit
        }
    }

    /** Last working port first, then search. Does nothing if already connected. */
    fun autoConnect(): Result<Unit> {
        if (isConnected()) return Result.success(Unit)
        val port = prefs.getInt("last_port", 0)
        if (port > 0) {
            val host = prefs.getString("last_host", "127.0.0.1") ?: "127.0.0.1"
            val r = connectManual(host, port, 5_000L)
            if (r.isSuccess) return r
        }
        return connectFind()
    }

    fun isConnected(): Boolean = runCatching {
        runWithTimeout(3_000L, "Checking the connection") { manager().isConnected() }
    }.getOrDefault(false)

    fun disconnect() {
        runCatching {
            runWithTimeout(3_000L, "Disconnecting") {
                manager().disconnect()
                Unit
            }
        }
    }

    fun shell(command: String): Result<String> = runCatching {
        runWithTimeout(8_000L, "The command") {
            val stream = manager().openStream("shell:$command")
            stream.openInputStream().bufferedReader().use { it.readText() }
        }
    }
}
