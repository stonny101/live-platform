package spp.platform.probe.util

import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.CertIOException
import org.bouncycastle.cert.X509ExtensionUtils
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.DigestCalculator
import org.bouncycastle.operator.OperatorCreationException
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.*

object SelfSignedCertGenerator {

    /**
     * Generates a self signed certificate using the BouncyCastle lib.
     *
     * @param keyPair used for signing the certificate with PrivateKey
     * @param hashAlgorithm Hash function
     * @param cn Common Name to be used in the subject dn
     * @param days validity period in days of the certificate
     *
     * @return self-signed X509Certificate
     *
     * @throws OperatorCreationException on creating a key id
     * @throws CertIOException on building JcaContentSignerBuilder
     * @throws CertificateException on getting certificate from provider
     */
    fun generate(
        keyPair: KeyPair, hashAlgorithm: String, cn: String, days: Int
    ): X509Certificate {
        val now = Instant.now()
        val notBefore = Date.from(now)
        val notAfter = Date.from(now.plus(Duration.ofDays(days.toLong())))
        val contentSigner = JcaContentSignerBuilder(hashAlgorithm).build(keyPair.private)
        val x500Name = X500Name("CN=$cn")
        val certificateBuilder = JcaX509v3CertificateBuilder(
            x500Name,
            BigInteger.valueOf(now.toEpochMilli()),
            notBefore,
            notAfter,
            x500Name,
            keyPair.public
        )
            .addExtension(Extension.subjectKeyIdentifier, false, createSubjectKeyId(keyPair.public))
            .addExtension(Extension.authorityKeyIdentifier, false, createAuthorityKeyId(keyPair.public))
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        return JcaX509CertificateConverter().getCertificate(certificateBuilder.build(contentSigner))
    }

    /**
     * Creates the hash value of the public key.
     *
     * @param publicKey of the certificate
     *
     * @return SubjectKeyIdentifier hash
     *
     * @throws OperatorCreationException
     */
    private fun createSubjectKeyId(publicKey: PublicKey): SubjectKeyIdentifier {
        val publicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        val digCalc: DigestCalculator =
            BcDigestCalculatorProvider().get(AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1))
        return X509ExtensionUtils(digCalc).createSubjectKeyIdentifier(publicKeyInfo)
    }

    /**
     * Creates the hash value of the authority public key.
     *
     * @param publicKey of the authority certificate
     *
     * @return AuthorityKeyIdentifier hash
     *
     * @throws OperatorCreationException
     */
    private fun createAuthorityKeyId(publicKey: PublicKey): AuthorityKeyIdentifier {
        val publicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        val digCalc: DigestCalculator =
            BcDigestCalculatorProvider().get(AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1))
        return X509ExtensionUtils(digCalc).createAuthorityKeyIdentifier(publicKeyInfo)
    }

    fun generateKeyPair(keySize: Int): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        val random = SecureRandom.getInstance("SHA1PRNG", "SUN")
        keyGen.initialize(keySize, random)
        return keyGen.generateKeyPair()
    }

    fun sign(inputCSR: PKCS10CertificationRequest, caPrivate: PrivateKey, pair: KeyPair): X509Certificate {
        val sigAlgId = DefaultSignatureAlgorithmIdentifierFinder().find("SHA1withRSA")
        val digAlgId = DefaultDigestAlgorithmIdentifierFinder().find(sigAlgId)
        val foo: AsymmetricKeyParameter = PrivateKeyFactory.createKey(caPrivate.encoded)
        val keyInfo = SubjectPublicKeyInfo.getInstance(pair.public.encoded)
        val myCertificateGenerator = X509v3CertificateBuilder(
            X500Name("CN=issuer"), BigInteger("1"),
            Date(
                System.currentTimeMillis()
            ), Date(
                System.currentTimeMillis() + (30 * 365 * 24 * 60 * 60 * 1000)
            ), inputCSR.subject, keyInfo
        )
        val sigGen = BcRSAContentSignerBuilder(sigAlgId, digAlgId).build(foo)
        val holder = myCertificateGenerator.build(sigGen)
        val eeX509CertificateStructure = holder.toASN1Structure()
        val cf: CertificateFactory = CertificateFactory.getInstance("X.509", "BC")

        val is1: InputStream = ByteArrayInputStream(eeX509CertificateStructure.encoded)
        val theCert = cf.generateCertificate(is1) as X509Certificate
        is1.close()
        return theCert
    }
}
