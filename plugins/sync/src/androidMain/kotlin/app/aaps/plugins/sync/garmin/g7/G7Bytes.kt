package app.aaps.plugins.sync.garmin.g7

// The byte helpers the copied G7 files use, from Bytes.kt in AndroidAPS PR #5397.

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Parses hex (upper or lower case). Returns null for an odd length or a non-hex character. */
fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val high = Character.digit(this[i * 2], 16)
        val low = Character.digit(this[i * 2 + 1], 16)
        if (high < 0 || low < 0) return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}

internal fun String.hexToBytes(): ByteArray = requireNotNull(hexToBytesOrNull()) { "Not hex: $this" }
