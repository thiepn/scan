package com.thiepn.scan.data

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

data class PdfSignatureValidation(
    val index: Int,
    val name: String,
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val reason: String,
    val location: String,
    val signingTimeMillis: Long?,
    val certificateNotBeforeMillis: Long?,
    val certificateNotAfterMillis: Long?,
    val cryptographicallyValid: Boolean,
    val certificateValidAtSigning: Boolean,
    val trustedByDevice: Boolean,
    val coversWholeDocument: Boolean,
    val message: String
)

data class PdfSignatureReport(
    val signatures: List<PdfSignatureValidation>
) {
    val hasSignatures: Boolean
        get() = signatures.isNotEmpty()

    val allCryptographicallyValid: Boolean
        get() = signatures.isNotEmpty() &&
            signatures.all { it.cryptographicallyValid }
}

data class SignedPdfResult(
    val file: File,
    val complianceReport: ComplianceReport,
    val signatureReport: PdfSignatureReport
)

object PdfDigitalSigner {
    private val provider = BouncyCastleProvider()

    fun sign(
        source: File,
        destination: File,
        pkcs12Input: InputStream,
        password: CharArray,
        reason: String = "",
        location: String = ""
    ): File {
        require(source.isFile) { "PDF source is unavailable" }
        require(destination.absolutePath != source.absolutePath) {
            "Signed output must use a different file"
        }
        destination.parentFile?.mkdirs()

        val material = loadKeyMaterial(pkcs12Input, password)
        val signingCertificate = material.certificates.first()

        PDDocument.load(source).use { document ->
            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(
                    PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED
                )
                setName(
                    signingCertificate.subjectX500Principal.name
                )
                setReason(reason.take(300))
                setLocation(location.take(300))
                setSignDate(Calendar.getInstance())
            }

            val signer = SignatureInterface { content ->
                createCmsSignature(content, material)
            }
            SignatureOptions().use { options ->
                options.preferredSignatureSize = 64 * 1024
                document.addSignature(
                    signature,
                    signer,
                    options
                )
                destination.outputStream()
                    .buffered()
                    .use { output ->
                        document.saveIncremental(output)
                    }
            }
        }

        return destination
    }

    private data class KeyMaterial(
        val privateKey: PrivateKey,
        val certificates: List<X509Certificate>
    )

    private fun loadKeyMaterial(
        input: InputStream,
        password: CharArray
    ): KeyMaterial {
        val store = KeyStore.getInstance("PKCS12")
        input.use { store.load(it, password) }
        val aliases = store.aliases()
        var keyAlias: String? = null
        while (aliases.hasMoreElements()) {
            val candidate = aliases.nextElement()
            if (store.isKeyEntry(candidate)) {
                keyAlias = candidate
                break
            }
        }
        val alias = keyAlias
            ?: throw IllegalArgumentException(
                "PKCS#12 file contains no private key"
            )
        val key = store.getKey(alias, password) as? PrivateKey
            ?: throw IllegalArgumentException(
                "PKCS#12 private key is unavailable"
            )
        val chain = store.getCertificateChain(alias)
            ?.mapNotNull { it as? X509Certificate }
            .orEmpty()
        require(chain.isNotEmpty()) {
            "PKCS#12 certificate chain is missing"
        }
        return KeyMaterial(key, chain)
    }

    private fun createCmsSignature(
        content: InputStream,
        material: KeyMaterial
    ): ByteArray {
        val algorithm = when (
            material.privateKey.algorithm.uppercase()
        ) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            "DSA" -> "SHA256withDSA"
            else -> throw IllegalArgumentException(
                "Unsupported signing key algorithm: " +
                    material.privateKey.algorithm
            )
        }

        val contentSigner = JcaContentSignerBuilder(algorithm)
            .setProvider(provider)
            .build(material.privateKey)
        val digestProvider =
            JcaDigestCalculatorProviderBuilder()
                .setProvider(provider)
                .build()
        val signerInfo =
            JcaSignerInfoGeneratorBuilder(digestProvider)
                .build(
                    contentSigner,
                    material.certificates.first()
                )

        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(signerInfo)
            addCertificates(
                JcaCertStore(material.certificates)
            )
        }

        val signed = generator.generate(
            StreamingTypedData(content),
            false
        )
        return signed.encoded
    }

    private class StreamingTypedData(
        private val input: InputStream
    ) : CMSTypedData {
        override fun getContentType(): ASN1ObjectIdentifier =
            CMSObjectIdentifiers.data

        override fun getContent(): Any = input

        override fun write(output: OutputStream) {
            input.use { it.copyTo(output) }
        }
    }
}

object PdfSignatureInspector {
    private val provider = BouncyCastleProvider()

    fun inspect(file: File): PdfSignatureReport {
        if (!file.isFile) return PdfSignatureReport(emptyList())
        val bytes = file.readBytes()
        val results = mutableListOf<PdfSignatureValidation>()

        runCatching {
            PDDocument.load(file).use { document ->
                document.signatureDictionaries
                    .forEachIndexed { index, signature ->
                        results += inspectSignature(
                            index,
                            signature,
                            bytes
                        )
                    }
            }
        }.onFailure { error ->
            results += PdfSignatureValidation(
                index = 0,
                name = "",
                subject = "",
                issuer = "",
                serialNumber = "",
                reason = "",
                location = "",
                signingTimeMillis = null,
                certificateNotBeforeMillis = null,
                certificateNotAfterMillis = null,
                cryptographicallyValid = false,
                certificateValidAtSigning = false,
                trustedByDevice = false,
                coversWholeDocument = false,
                message = error.message
                    ?: "Could not inspect PDF signatures."
            )
        }

        return PdfSignatureReport(results)
    }

    private fun inspectSignature(
        index: Int,
        signature: PDSignature,
        pdfBytes: ByteArray
    ): PdfSignatureValidation {
        val signingDate = signature.signDate?.time
        return runCatching {
            val signedContent =
                signature.getSignedContent(pdfBytes)
            val contents = signature.getContents(pdfBytes)
            val cms = CMSSignedData(
                org.bouncycastle.cms.CMSProcessableByteArray(
                    signedContent
                ),
                contents
            )
            val signer = cms.signerInfos.signers.firstOrNull()
                ?: throw IllegalStateException(
                    "CMS signature contains no signer"
                )
            val certificateStore = cms.certificates
            @Suppress("UNCHECKED_CAST")
            val signerSelector = signer.sid as
                org.bouncycastle.util.Selector<X509CertificateHolder>
            val holder = certificateStore
                .getMatches(signerSelector)
                .firstOrNull()
                ?: throw IllegalStateException(
                    "Signing certificate is missing"
                )
            val certificate =
                JcaX509CertificateConverter()
                    .setProvider(provider)
                    .getCertificate(
                        holder as X509CertificateHolder
                    )
            val cryptoValid = signer.verify(
                JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(provider)
                    .build(certificate)
            )

            val allHolders:
                Collection<X509CertificateHolder> =
                certificateStore.getMatches(null)
            val allCertificates = allHolders
                .mapNotNull { certHolder ->
                    runCatching {
                        JcaX509CertificateConverter()
                            .setProvider(provider)
                            .getCertificate(certHolder)
                    }.getOrNull()
                }

            val validAtSigning = runCatching {
                certificate.checkValidity(
                    signingDate ?: Date()
                )
                true
            }.getOrDefault(false)

            val trusted = isTrustedByDevice(
                certificate,
                allCertificates,
                signingDate
            )
            val range = signature.byteRange
            val coversWhole = range.size == 4 &&
                range[0] == 0 &&
                range[2] >= 0 &&
                range[3] >= 0 &&
                range[2].toLong() +
                    range[3].toLong() ==
                    pdfBytes.size.toLong()

            PdfSignatureValidation(
                index = index,
                name = signature.name.orEmpty(),
                subject = certificate
                    .subjectX500Principal.name,
                issuer = certificate
                    .issuerX500Principal.name,
                serialNumber = certificate.serialNumber
                    .toString(16)
                    .uppercase(),
                reason = signature.reason.orEmpty(),
                location = signature.location.orEmpty(),
                signingTimeMillis = signingDate?.time,
                certificateNotBeforeMillis =
                    certificate.notBefore.time,
                certificateNotAfterMillis =
                    certificate.notAfter.time,
                cryptographicallyValid = cryptoValid,
                certificateValidAtSigning =
                    validAtSigning,
                trustedByDevice = trusted,
                coversWholeDocument = coversWhole,
                message = when {
                    !cryptoValid ->
                        "Signature does not verify cryptographically."
                    !validAtSigning ->
                        "Certificate was not valid at the recorded signing time."
                    !coversWhole ->
                        "Signature covers an earlier PDF revision; later changes or signatures exist."
                    trusted ->
                        "Signature is valid and chains to a device-trusted certificate authority; revocation status was not checked."
                    else ->
                        "Signature is cryptographically valid, but the certificate chain is not trusted by this device."
                }
            )
        }.getOrElse { error ->
            PdfSignatureValidation(
                index = index,
                name = signature.name.orEmpty(),
                subject = "",
                issuer = "",
                serialNumber = "",
                reason = signature.reason.orEmpty(),
                location = signature.location.orEmpty(),
                signingTimeMillis = signingDate?.time,
                certificateNotBeforeMillis = null,
                certificateNotAfterMillis = null,
                cryptographicallyValid = false,
                certificateValidAtSigning = false,
                trustedByDevice = false,
                coversWholeDocument = false,
                message = error.message
                    ?: "Signature validation failed."
            )
        }
    }

    private fun isTrustedByDevice(
        certificate: X509Certificate,
        certificates: Collection<X509Certificate>,
        signingDate: Date?
    ): Boolean = runCatching {
        val trustStore = KeyStore.getInstance(
            "AndroidCAStore"
        ).apply {
            load(null)
        }
        val selector = X509CertSelector().apply {
            this.certificate = certificate
        }
        val parameters = PKIXBuilderParameters(
            trustStore,
            selector
        ).apply {
            isRevocationEnabled = false
            if (signingDate != null) {
                date = signingDate
            }
            addCertStore(
                CertStore.getInstance(
                    "Collection",
                    CollectionCertStoreParameters(
                        certificates
                    )
                )
            )
        }
        CertPathBuilder.getInstance("PKIX")
            .build(parameters)
        true
    }.getOrDefault(false)
}
