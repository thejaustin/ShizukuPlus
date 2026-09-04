package rikka.shizuku.server

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.Base64

/**
 * Signs an APK with V1 (JAR) + V2 signatures using an ephemeral RSA-2048 key.
 * No external tools required — pure JVM crypto from ART's built-in providers.
 *
 * Usage:
 *   val signed = ApkSigner.sign(patchedApkBytes)
 */
internal object ApkSigner {

    // ── Key pair (generated once per process lifetime) ─────────────────────

    private val keyPair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
    private val privateKey: PrivateKey get() = keyPair.private
    private val publicKey: PublicKey  get() = keyPair.public

    // ── DER encoding primitives ─────────────────────────────────────────────

    private fun derLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), (n and 0xFF).toByte())
    }

    private fun tlv(tag: Int, data: ByteArray): ByteArray {
        val len = derLen(data.size)
        val out = ByteArray(1 + len.size + data.size)
        out[0] = tag.toByte()
        System.arraycopy(len, 0, out, 1, len.size)
        System.arraycopy(data, 0, out, 1 + len.size, data.size)
        return out
    }

    private fun seq(vararg items: ByteArray)  = tlv(0x30, concat(*items))
    private fun set(vararg items: ByteArray)  = tlv(0x31, concat(*items))
    private fun oid(vararg arcs: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            val v = arcs[i]
            if (v < 0x80) { body.write(v) }
            else {
                val bits = mutableListOf<Int>()
                var rem = v
                while (rem > 0) { bits.add(0, rem and 0x7F); rem = rem ushr 7 }
                for (j in 0 until bits.size - 1) body.write(bits[j] or 0x80)
                body.write(bits.last())
            }
        }
        return tlv(0x06, body.toByteArray())
    }
    private fun int(data: ByteArray)   = tlv(0x02, data)
    private fun oct(data: ByteArray)   = tlv(0x04, data)
    private fun bits(data: ByteArray)  = tlv(0x03, byteArrayOf(0) + data)
    private fun utf8(s: String)        = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun ctx(n: Int, d: ByteArray) = tlv(0xA0 or n, d)
    private fun utcTime(s: String)     = tlv(0x17, s.toByteArray(Charsets.US_ASCII))
    private fun nullDer()              = byteArrayOf(0x05, 0x00)
    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) { System.arraycopy(p, 0, out, pos, p.size); pos += p.size }
        return out
    }

    // Encode a positive BigInteger-style byte array with a leading 0x00 if high bit set
    private fun posInt(b: ByteArray) = int(if (b[0].toInt() and 0x80 != 0) byteArrayOf(0) + b else b)

    // ── X.509 self-signed certificate (DER) ────────────────────────────────

    private val certDer: ByteArray by lazy { buildCert() }

    private fun buildCert(): ByteArray {
        val rsaPub = publicKey.encoded  // SubjectPublicKeyInfo DER from JVM

        // OIDs
        val oidSha256WithRsa = oid(1, 2, 840, 113549, 1, 1, 11)
        val oidCn            = oid(2, 5, 4, 3)
        val sigAlg = seq(oidSha256WithRsa, nullDer())

        val name = seq(set(seq(oidCn, utf8("ShizukuPlus"))))
        val validity = seq(utcTime("200101000000Z"), utcTime("491231235959Z"))
        val spki = ByteArray(rsaPub.size).also { System.arraycopy(rsaPub, 0, it, 0, rsaPub.size) }

        // Serial number: 1
        val serial = posInt(byteArrayOf(0x01))

        val tbsCert = seq(
            ctx(0, byteArrayOf(0x02)),  // version = v3
            serial,
            sigAlg,
            name,           // issuer
            validity,
            name,           // subject
            spki
        )

        // Sign tbsCert
        val rawSig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(tbsCert)
        }.sign()

        return seq(tbsCert, sigAlg, bits(rawSig))
    }

    // ── V1 Signing (JAR / MANIFEST.MF + CERT.SF + CERT.RSA) ───────────────

    private fun sha256b64(data: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data))

    private fun manifestSection(name: String, data: ByteArray): String {
        val sb = StringBuilder()
        sb.append("Name: $name\r\n")
        sb.append("SHA-256-Digest: ${sha256b64(data)}\r\n")
        sb.append("\r\n")
        return sb.toString()
    }

    /**
     * Strips old META-INF signature files and re-signs with V1.
     * Returns the new ZIP bytes (unsigned entries only + new META-INF/).
     */
    private fun signV1(entries: List<Pair<String, ByteArray>>): ByteArray {
        val manifest = StringBuilder()
        manifest.append("Manifest-Version: 1.0\r\nCreated-By: 1.0 (ShizukuPlus)\r\n\r\n")

        val sections = StringBuilder()
        for ((name, data) in entries) {
            val section = manifestSection(name, data)
            manifest.append(section)
            sections.append(section)
        }

        val mfBytes = manifest.toString().toByteArray(Charsets.UTF_8)

        // CERT.SF
        val sf = StringBuilder()
        sf.append("Signature-Version: 1.0\r\n")
        sf.append("SHA-256-Digest-Manifest: ${sha256b64(mfBytes)}\r\n")
        sf.append("Created-By: 1.0 (ShizukuPlus)\r\n\r\n")
        for ((name, data) in entries) {
            val section = manifestSection(name, data)
            val sectionBytes = section.toByteArray(Charsets.UTF_8)
            sf.append("Name: $name\r\n")
            sf.append("SHA-256-Digest: ${sha256b64(sectionBytes)}\r\n")
            sf.append("\r\n")
        }
        val sfBytes = sf.toString().toByteArray(Charsets.UTF_8)

        // CERT.RSA — PKCS#7 detached signature
        val certRsa = buildPkcs7(sfBytes)

        // Repack ZIP
        val out = ByteArrayOutputStream()
        val zos = ZipOutputStream(out)
        zos.setMethod(ZipOutputStream.DEFLATED)
        for ((name, data) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(data)
            zos.closeEntry()
        }
        fun addMeta(name: String, data: ByteArray) {
            val e = ZipEntry(name); e.method = ZipEntry.DEFLATED
            zos.putNextEntry(e); zos.write(data); zos.closeEntry()
        }
        addMeta("META-INF/MANIFEST.MF", mfBytes)
        addMeta("META-INF/CERT.SF", sfBytes)
        addMeta("META-INF/CERT.RSA", certRsa)
        zos.finish()
        return out.toByteArray()
    }

    // PKCS#7 SignedData (detached, no content, SHA-256, RSA)
    private fun buildPkcs7(toBeSigned: ByteArray): ByteArray {
        val oidData           = oid(1, 2, 840, 113549, 1, 7, 1)
        val oidSignedData     = oid(1, 2, 840, 113549, 1, 7, 2)
        val oidSha256         = oid(2, 16, 840, 1, 101, 3, 4, 2, 1)
        val oidRsa            = oid(1, 2, 840, 113549, 1, 1, 1)
        val oidSha256WithRsa  = oid(1, 2, 840, 113549, 1, 1, 11)

        val certBytes = certDer
        val digest = MessageDigest.getInstance("SHA-256").digest(toBeSigned)

        val rawSig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(toBeSigned)
        }.sign()

        // IssuerAndSerialNumber
        val issuer = seq(set(seq(oid(2, 5, 4, 3), utf8("ShizukuPlus"))))
        val serial = posInt(byteArrayOf(0x01))
        val issuerAndSerial = seq(issuer, serial)

        val digestAlgId    = seq(oidSha256, nullDer())
        val encryptAlgId   = seq(oidRsa,  nullDer())

        val signerInfo = seq(
            int(byteArrayOf(0x01)),   // version
            issuerAndSerial,
            digestAlgId,
            encryptAlgId,
            oct(rawSig)
        )

        val digestAlgorithms = set(digestAlgId)

        val encapContentInfo = seq(oidData)  // detached: no content

        val signedData = seq(
            int(byteArrayOf(0x01)),            // version
            digestAlgorithms,
            encapContentInfo,
            tlv(0xA0, certBytes),              // [0] certificates
            set(signerInfo)                    // signerInfos
        )

        return seq(oidSignedData, ctx(0, signedData))
    }

    // ── V2 APK Signature Block ──────────────────────────────────────────────

    private fun Int.le(): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(this).array()
    private fun Long.le(): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(this).array()

    private fun lengthPrefixed(data: ByteArray): ByteArray = data.size.le() + data
    private fun lengthPrefixed64(data: ByteArray): ByteArray = data.size.toLong().le() + data

    private const val CHUNK_SIZE = 1024 * 1024  // 1 MB

    private fun digestSection(data: ByteArray, offset: Int, length: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val chunkLen = minOf(CHUNK_SIZE, end - pos)
            val md = MessageDigest.getInstance("SHA-256")
            md.update(0xa5.toByte())
            md.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkLen).array())
            md.update(data, pos, chunkLen)
            chunks.add(md.digest())
            pos += chunkLen
        }
        return chunks
    }

    private fun topLevelDigest(chunkDigests: List<ByteArray>): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(0x5a.toByte())
        md.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkDigests.size).array())
        for (chunk in chunkDigests) md.update(chunk)
        return md.digest()
    }

    private fun locateZipSections(apk: ByteArray): Triple<Int, Int, Int> {
        // Locate EOCD by scanning backwards for signature 0x06054b50
        var eocdOffset = apk.size - 22
        while (eocdOffset >= 0) {
            val sig = ByteBuffer.wrap(apk, eocdOffset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (sig == 0x06054b50) break
            eocdOffset--
        }
        if (eocdOffset < 0) error("No EOCD found")
        val cdOffset = ByteBuffer.wrap(apk, eocdOffset + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val cdSize   = ByteBuffer.wrap(apk, eocdOffset + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return Triple(cdOffset, cdSize, eocdOffset)
    }

    private fun buildV2Block(apkBeforeBlock: ByteArray, cdBytes: ByteArray, eocdWithNewOffset: ByteArray): ByteArray {
        val sec1 = digestSection(apkBeforeBlock, 0, apkBeforeBlock.size)
        val sec2 = digestSection(cdBytes, 0, cdBytes.size)
        val sec3 = digestSection(eocdWithNewOffset, 0, eocdWithNewOffset.size)

        val allChunks = sec1 + sec2 + sec3
        val apkDigest = topLevelDigest(allChunks)

        // Signed data structure for V2
        val algoId     = 0x0103.le()   // RSASSA-PKCS1-v1_5 with SHA2-256
        val digestPair = lengthPrefixed(algoId + lengthPrefixed(apkDigest))
        val digests    = lengthPrefixed(digestPair)
        val certs      = lengthPrefixed(lengthPrefixed(certDer))
        val attributes = lengthPrefixed(ByteArray(0))  // empty
        val signedData = lengthPrefixed(digests + certs + attributes)

        // Signature
        val rawSig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signedData)
        }.sign()
        val sigPair  = lengthPrefixed(algoId + lengthPrefixed(rawSig))
        val sigs     = lengthPrefixed(sigPair)

        // Public key (SubjectPublicKeyInfo DER)
        val pubKeyBytes = publicKey.encoded
        val pubKeyLp    = lengthPrefixed(pubKeyBytes)

        val signer      = lengthPrefixed(signedData + sigs + pubKeyLp)
        val signerList  = lengthPrefixed(signer)

        // ID-value pair for V2 block (ID = 0x7109871a)
        val blockId  = 0x7109871a.le()
        val idValue  = lengthPrefixed64(blockId + signerList)

        // APK Signing Block
        val idValues   = idValue
        val blockSize  = (idValues.size + 8 + 16).toLong()  // pairs + size64 + magic
        return blockSize.le() + idValues + blockSize.le() + "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    }

    private fun updateEocdCdOffset(eocd: ByteArray, newCdOffset: Int): ByteArray {
        val result = eocd.copyOf()
        ByteBuffer.wrap(result, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(newCdOffset)
        return result
    }

    // ── Public entry point ──────────────────────────────────────────────────

    /**
     * Strip old signatures, add V1+V2 signatures. Returns the signed APK bytes.
     */
    fun sign(apkBytes: ByteArray): ByteArray {
        // 1. Read all entries, strip old META-INF sig files
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(apkBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!name.startsWith("META-INF/") || name == "META-INF/") {
                    entries.add(name to zis.readBytes())
                } else {
                    val upper = name.uppercase()
                    // Keep only non-signature META-INF entries
                    if (!upper.endsWith(".SF") && !upper.endsWith(".RSA") &&
                        !upper.endsWith(".DSA") && !upper.endsWith(".EC") &&
                        upper != "META-INF/MANIFEST.MF") {
                        entries.add(name to zis.readBytes())
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 2. Build V1-signed ZIP (MANIFEST.MF + CERT.SF + CERT.RSA inside)
        val v1Apk = signV1(entries)

        // 3. Insert V2 signing block before the Central Directory
        val (cdOffset, cdSize, eocdOffset) = locateZipSections(v1Apk)
        val cdBytes   = v1Apk.copyOfRange(cdOffset, cdOffset + cdSize)
        val eocdBytes = v1Apk.copyOfRange(eocdOffset, v1Apk.size)

        val v2Block = buildV2Block(v1Apk.copyOfRange(0, cdOffset), cdBytes, updateEocdCdOffset(eocdBytes, cdOffset /* placeholder for size calc */))

        val newCdOffset = cdOffset + v2Block.size
        val eocdPatched = updateEocdCdOffset(eocdBytes, newCdOffset)

        // Rebuild: [entries section][v2 block][central directory][eocd]
        return v1Apk.copyOfRange(0, cdOffset) + v2Block + cdBytes + eocdPatched
    }
}
