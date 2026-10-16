package com.example.questlauncher

import android.content.Context
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

/** Small wrapper the rest of the app uses. Every call blocks, so call it off the main thread. */
class AdbController(private val context: Context) {

    private fun manager(): AdbManager = AdbManager.get(context)

    fun pair(host: String, port: Int, code: String): Result<Unit> = runCatching {
        val ok = manager().pair(host, port, code)
        if (!ok) {
            error("Pairing was refused. Check the port and code, and keep the pairing window open.")
        }
    }

    /** port == null means: find it automatically on the local network. */
    fun connect(host: String, port: Int?): Result<Unit> = runCatching {
        val m = manager()
        val ok = if (port == null) m.autoConnect(context, 8_000L) else m.connect(host, port)
        if (!ok && !m.isConnected()) {
            error("Couldn't connect. Check that Wireless debugging is on.")
        }
    }

    fun isConnected(): Boolean = runCatching { manager().isConnected() }.getOrDefault(false)

    fun disconnect() {
        runCatching { manager().disconnect() }
    }

    fun shell(command: String): Result<String> = runCatching {
        val stream = manager().openStream("shell:$command")
        stream.openInputStream().bufferedReader().use { it.readText() }
    }
}
