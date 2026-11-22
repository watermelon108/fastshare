package com.fastshare.app

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** This device's self-signed certificate. Same device keeps the same cert across restarts. */
class Identity(val cert: X509Certificate, val key: PrivateKey) {
    val fingerprint: String = sha256Hex(cert.encoded)
}

object Crypto {

    fun loadOrCreate(dir: File): Identity {
        val certFile = File(dir, "cert.der")
        val keyFile = File(dir, "key.pk8")
        if (certFile.exists() && keyFile.exists()) {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            return Identity(cert, key)
        }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val subject = X500Name("CN=fastshare")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 3650L * 86_400_000L),
            subject,
            kp.public
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        certFile.writeBytes(cert.encoded)
        keyFile.writeBytes(kp.private.encoded)
        return Identity(cert, kp.private)
    }

    /** Server side: present our cert, do not ask for a client cert (auth is done with the pairing key). */
    fun serverContext(id: Identity): SSLContext {
        val km = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = arrayOf("fs")
            override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = "fs"
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(id.cert)
            override fun getPrivateKey(alias: String?): PrivateKey = id.key
        }
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf(km), null, null)
        }
    }

    /** Client side: devices are self-signed, so we accept the cert and verify the peer with the pairing key. */
    val clientContext: SSLContext by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }
    }

    fun hmac(key: ByteArray, msg: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8))
    }

    /** Same as Linux: HMAC over "a|b|c" with lowercase hex output. */
    fun tag(key: ByteArray, vararg parts: String): String = hmac(key, parts.joinToString("|")).toHex()

    fun pairProof(code: String, fpC: String, fpS: String): String =
        tag(code.toByteArray(Charsets.UTF_8), "proof", fpC, fpS)

    fun pairKey(code: String, fpC: String, fpS: String): ByteArray =
        hmac(code.toByteArray(Charsets.UTF_8), "key|$fpC|$fpS")

    fun safeEq(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

/** Same rules as the Linux app: no path parts, no unsafe chars, max 200 chars. */
fun safeName(raw: String): String {
    var n = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    n = n.replace(Regex("[\\x00-\\x1f<>:\"/\\\\|?*]"), "_")
    if (n.isEmpty() || n == "." || n == "..") n = "file"
    if (n.startsWith(".")) n = "_$n"
    return n.take(200)
}
