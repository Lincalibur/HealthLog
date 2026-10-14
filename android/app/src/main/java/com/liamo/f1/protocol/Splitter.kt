package com.liamo.f1.protocol

/**
 * Wearfit Pro's OrderUtil.handleMessageBytes: the first [size] bytes go as-is,
 * each continuation is `[seq]` + the next size-1 bytes, with seq = 0, 1, 2, ...
 */
fun split(data: ByteArray, size: Int = CHUNK_SIZE): List<ByteArray> {
    if (data.size <= size) return listOf(data)
    val out = mutableListOf(data.copyOfRange(0, size))
    var i = size
    var seq = 0
    while (i < data.size) {
        val end = minOf(i + size - 1, data.size)
        out += byteArrayOf(seq.toByte()) + data.copyOfRange(i, end)
        i = end
        seq++
    }
    return out
}
