package rikka.shizuku.server

import java.io.ByteArrayOutputStream

/**
 * Patches an Android binary XML (AndroidManifest.xml) to set android:debuggable="true".
 *
 * Handles all three cases:
 *   A) android:debuggable="false" explicitly present → flip value byte in place
 *   B) "debuggable" is in the string pool but the attribute is absent → add the attribute
 *   C) "debuggable" is absent from the string pool → add string + extend resource map + add attribute
 */
internal object ApkBinaryXmlPatcher {

    private const val TYPE_STRING_POOL    = 0x0001
    private const val TYPE_XML_FILE       = 0x0003
    private const val TYPE_RESOURCE_MAP   = 0x0180
    private const val TYPE_START_ELEMENT  = 0x0102

    private const val STR_ANDROID_NS  = "http://schemas.android.com/apk/res/android"
    private const val STR_DEBUGGABLE  = "debuggable"
    private const val STR_APPLICATION = "application"
    private const val RES_DEBUGGABLE  = 0x0101021b

    private const val FLAG_UTF8           = 0x00000100
    private const val TYPE_INT_BOOLEAN    = 0x12.toByte()

    // ── Byte-level helpers ────────────────────────────────────────────────────

    private fun ByteArray.i32(p: Int) =
        ((this[p].toInt() and 0xFF)        ) or
        ((this[p+1].toInt() and 0xFF) shl  8) or
        ((this[p+2].toInt() and 0xFF) shl 16) or
        ((this[p+3].toInt() and 0xFF) shl 24)

    private fun ByteArray.i16(p: Int) =
        ((this[p].toInt() and 0xFF)) or ((this[p+1].toInt() and 0xFF) shl 8)

    private fun ByteArray.p32(p: Int, v: Int) {
        this[p]   = (v         and 0xFF).toByte()
        this[p+1] = ((v ushr  8) and 0xFF).toByte()
        this[p+2] = ((v ushr 16) and 0xFF).toByte()
        this[p+3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun ByteArray.p16(p: Int, v: Int) {
        this[p]   = (v       and 0xFF).toByte()
        this[p+1] = ((v ushr 8) and 0xFF).toByte()
    }

    /** Return a new array with [data] inserted at [at]. */
    private fun ByteArray.ins(at: Int, data: ByteArray): ByteArray {
        val out = ByteArray(size + data.size)
        System.arraycopy(this,  0,   out, 0,               at)
        System.arraycopy(data,  0,   out, at,              data.size)
        System.arraycopy(this,  at,  out, at + data.size,  size - at)
        return out
    }

    // ── String pool ───────────────────────────────────────────────────────────

    private class PoolInfo(
        val start: Int,           // byte offset of pool chunk in file
        val size: Int,            // current chunk byte size
        val count: Int,           // number of strings
        val isUtf8: Boolean,
        val stringsStart: Int,    // offset from pool.start to first string byte
        val stylesStart: Int,     // 0 if no styles
        val styleCount: Int,
        val offsets: IntArray,    // string offsets, relative to stringsStart (from pool.start)
        val strings: List<String>
    ) {
        /** Total bytes used by string data only (without style data). */
        val strDataSize: Int get() =
            if (stylesStart > 0) stylesStart - stringsStart else size - stringsStart
    }

    private fun readStr(bytes: ByteArray, base: Int, isUtf8: Boolean): String = runCatching {
        if (isUtf8) {
            var p = base
            // Skip UTF-16 length (1 or 2 bytes with high-bit continuation)
            var b = bytes[p++].toInt() and 0xFF
            if ((b and 0x80) != 0) p++
            // Read UTF-8 byte length
            b = bytes[p++].toInt() and 0xFF
            val len = if ((b and 0x80) != 0)
                ((b and 0x7F) shl 8) or (bytes[p++].toInt() and 0xFF) else b
            String(bytes, p, len, Charsets.UTF_8)
        } else {
            val charCount = bytes.i16(base)
            buildString(charCount) { repeat(charCount) { i -> append(bytes.i16(base + 2 + i * 2).toChar()) } }
        }
    }.getOrDefault("")

    private fun parsePool(bytes: ByteArray): PoolInfo {
        val start = 8  // string pool always follows the 8-byte XML file header
        val size        = bytes.i32(start + 4)
        val count       = bytes.i32(start + 8)
        val styleCount  = bytes.i32(start + 12)
        val flags       = bytes.i32(start + 16)
        val stringsStart= bytes.i32(start + 20)
        val stylesStart = bytes.i32(start + 24)
        val isUtf8 = (flags and FLAG_UTF8) != 0
        val offsets = IntArray(count) { i -> bytes.i32(start + 28 + i * 4) }
        val strings = List(count) { i -> readStr(bytes, start + stringsStart + offsets[i], isUtf8) }
        return PoolInfo(start, size, count, isUtf8, stringsStart, stylesStart, styleCount, offsets, strings)
    }

    // ── Resource map ──────────────────────────────────────────────────────────

    private class ResMapInfo(val start: Int, val size: Int, val ids: IntArray)

    private fun parseResMap(bytes: ByteArray, poolEnd: Int): ResMapInfo? {
        if (poolEnd + 8 > bytes.size) return null
        if (bytes.i16(poolEnd) != TYPE_RESOURCE_MAP) return null
        val size  = bytes.i32(poolEnd + 4)
        val count = (size - 8) / 4
        return ResMapInfo(poolEnd, size, IntArray(count) { i -> bytes.i32(poolEnd + 8 + i * 4) })
    }

    // ── Element search ────────────────────────────────────────────────────────

    private class ElemInfo(
        val chunkStart: Int,
        val chunkSize: Int,
        val attrCount: Int,
        val attrsStart: Int  // absolute offset of first attribute byte in file
    )

    private fun findAppElem(bytes: ByteArray, pool: PoolInfo, resMap: ResMapInfo?, appIdx: Int): ElemInfo? {
        val xmlSize = bytes.i32(4)
        var pos = pool.start + pool.size
        if (resMap != null) pos += resMap.size
        while (pos + 8 <= xmlSize) {
            val type = bytes.i16(pos)
            val chunkSize = bytes.i32(pos + 4)
            if (chunkSize <= 0) break
            if (type == TYPE_START_ELEMENT) {
                val nameIdx = bytes.i32(pos + 20)
                if (nameIdx == appIdx) {
                    val attrCount = bytes.i16(pos + 28)
                    return ElemInfo(pos, chunkSize, attrCount, pos + 36)
                }
            }
            pos += chunkSize
        }
        return null
    }

    /** Returns absolute offset of debuggable attr's first byte, or -1 if not found. */
    private fun findDebugAttr(bytes: ByteArray, elem: ElemInfo, debugIdx: Int): Int {
        if (debugIdx < 0) return -1
        repeat(elem.attrCount) { i ->
            val off = elem.attrsStart + i * 20
            if (bytes.i32(off + 4) == debugIdx) return off
        }
        return -1
    }

    // ── Pool string encoding ──────────────────────────────────────────────────

    private fun encodeStr(s: String, isUtf8: Boolean): ByteArray {
        if (isUtf8) {
            val utf8 = s.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream()
            // UTF-16 length (1 or 2 bytes)
            val u16 = s.length
            if (u16 >= 0x80) out.write(0x80 or (u16 ushr 8)); out.write(u16 and 0x7F)
            // UTF-8 byte count (1 or 2 bytes)
            val u8 = utf8.size
            if (u8 >= 0x80) out.write(0x80 or (u8 ushr 8)); out.write(u8 and 0x7F)
            out.write(utf8)
            out.write(0)
            return out.toByteArray()
        } else {
            val n = s.length
            val b = ByteArray(2 + n * 2 + 2)  // null-terminator already zeroed
            b.p16(0, n)
            repeat(n) { i -> b.p16(2 + i * 2, s[i].code) }
            return b
        }
    }

    // ── Case C: add "debuggable" to pool + resource map ───────────────────────

    private fun addToPool(
        bytes: ByteArray, pool: PoolInfo, resMap: ResMapInfo?,
        str: String, resId: Int
    ): ByteArray {
        val strBytes = encodeStr(str, pool.isUtf8)
        val newStrOffset = pool.strDataSize  // position within string data section

        // 4-byte LE encoding of the new string's offset
        val newOffEntry = ByteArray(4).also { it.p32(0, newStrOffset) }

        // Insert the offset entry right after the existing offset table
        val offsetInsertPos = pool.start + 28 + pool.count * 4
        var result = bytes.ins(offsetInsertPos, newOffEntry)

        // Append string bytes at end of (now-shifted) string data
        val strDataEnd = pool.start + (pool.stringsStart + 4) + pool.strDataSize
        result = result.ins(strDataEnd, strBytes)

        val poolGrowth = 4 + strBytes.size

        // Update pool header: stringCount, stringsStart, chunkSize
        result.p32(pool.start + 8,  pool.count + 1)
        result.p32(pool.start + 20, pool.stringsStart + 4)
        if (pool.stylesStart > 0) result.p32(pool.start + 24, pool.stylesStart + 4)
        result.p32(pool.start + 4,  pool.size + poolGrowth)

        // Update XML root chunk size
        result.p32(4, result.i32(4) + poolGrowth)

        // Extend resource map to cover newStringIdx = pool.count
        if (resMap != null) {
            val newStringIdx = pool.count
            val newResMapStart = pool.start + pool.size + poolGrowth
            val currentEntries = resMap.ids.size
            val entriesToAdd = newStringIdx - currentEntries + 1  // zero-pad + one real entry
            if (entriesToAdd > 0) {
                val extra = ByteArray(entriesToAdd * 4)
                // Write resId at the last slot (for newStringIdx)
                extra.p32((entriesToAdd - 1) * 4, resId)
                val resMapDataEnd = newResMapStart + resMap.size
                result = result.ins(resMapDataEnd, extra)
                result.p32(newResMapStart + 4, resMap.size + extra.size)
                result.p32(4, result.i32(4) + extra.size)
            }
        }

        return result
    }

    // ── Add attribute to <application> element ────────────────────────────────

    private fun addAttr(bytes: ByteArray, elem: ElemInfo, debugIdx: Int, nsIdx: Int): ByteArray {
        val attr = ByteArray(20).apply {
            p32( 0, nsIdx)           // namespace string index
            p32( 4, debugIdx)        // name string index
            p32( 8, -1)              // rawValue = none
            p16(12,  8)              // value.size
            this[14] = 0             // value.res0
            this[15] = TYPE_INT_BOOLEAN
            p32(16, 1)               // value.data = true
        }
        val insertPos = elem.attrsStart + elem.attrCount * 20
        val result = bytes.ins(insertPos, attr)
        result.p32(elem.chunkStart + 4,  elem.chunkSize + 20)
        result.p16(elem.chunkStart + 28, elem.attrCount + 1)
        result.p32(4, result.i32(4) + 20)
        return result
    }

    // ── Public entry point ────────────────────────────────────────────────────

    fun patch(input: ByteArray): ByteArray {
        var bytes = input.copyOf()

        val pool    = parsePool(bytes)
        val resMap  = parseResMap(bytes, pool.start + pool.size)
        val debugIdx = pool.strings.indexOf(STR_DEBUGGABLE)
        val appIdx   = pool.strings.indexOf(STR_APPLICATION)
        if (appIdx < 0) return bytes

        val elem = findAppElem(bytes, pool, resMap, appIdx) ?: return bytes

        // Case A: attribute exists → just flip the value
        val existingOff = findDebugAttr(bytes, elem, debugIdx)
        if (existingOff >= 0) {
            bytes.p32(existingOff + 16, 1)
            return bytes
        }

        // Case C: string not in pool → add string + extend resource map
        if (debugIdx < 0) {
            bytes = addToPool(bytes, pool, resMap, STR_DEBUGGABLE, RES_DEBUGGABLE)
            val pool2   = parsePool(bytes)
            val resMap2 = parseResMap(bytes, pool2.start + pool2.size)
            val dIdx2   = pool2.strings.indexOf(STR_DEBUGGABLE)
            val aIdx2   = pool2.strings.indexOf(STR_APPLICATION)
            val nsIdx2  = pool2.strings.indexOf(STR_ANDROID_NS)
            val elem2   = findAppElem(bytes, pool2, resMap2, aIdx2) ?: return bytes
            return addAttr(bytes, elem2, dIdx2, nsIdx2)
        }

        // Case B: string in pool but attribute absent
        val nsIdx = pool.strings.indexOf(STR_ANDROID_NS)
        return addAttr(bytes, elem, debugIdx, nsIdx)
    }
}
