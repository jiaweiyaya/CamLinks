package com.jiaweiya.camlinks.util

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object CertificateHelper {
    fun createServerSslContext(): SSLContext {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        val notBefore = Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        val notAfter = Date(System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L)
        val subject = X500Name("CN=CamLinksServer")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(certHolder)

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        val password = "camlinks_password".toCharArray()
        keyStore.setKeyEntry("camlinks_key", keyPair.private, password, arrayOf(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)
        return sslContext
    }
}