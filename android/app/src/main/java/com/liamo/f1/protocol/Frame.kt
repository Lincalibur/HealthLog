package com.liamo.f1.protocol

// Pure-Kotlin port of the WearFit protocol in tools/genius_f1.py. No Android code in this package.

const val HEADER_AB = 0xAB
const val HEADER_EA = 0xEA

/** The watch takes 20-byte writes; Wearfit Pro and the laptop tools both split to this size. */
const val CHUNK_SIZE = 20

/** `AB <len hi> <len lo> FF <cmd> <kind> <args...>`, where len = args.size + 3. */
fun frame(cmd: Int, args: ByteArray = byteArrayOf(), kind: Int = 0x80, header: Int = HEADER_AB): ByteArray {
    val n = args.size + 3
    return byteArrayOf(
        header.toByte(), (n shr 8).toByte(), n.toByte(), 0xFF.toByte(), cmd.toByte(), kind.toByte(),
    ) + args
}

fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** Parses "ab0003ff7180", "AB 00 03 FF 71 80" or "ab:00:03". Throws on odd length or bad digits. */
fun hexToBytes(hex: String): ByteArray {
    val clean = hex.filter { !it.isWhitespace() && it != ':' && it != '-' }
    require(clean.length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
