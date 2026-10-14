package com.liamo.f1.protocol

/**
 * Mirror of Wearfit Pro's DataPackager.packageData(): the total length comes from
 * bytes 1-2 of the first packet (+3), and every continuation packet's first byte
 * (its sequence number) is dropped.
 */
class Reassembler {
    private var buf: ByteArray? = null
    private var total = 0

    /** Returns a complete frame, or null while one is still being collected. */
    fun feed(packet: ByteArray): ByteArray? {
        val pending = buf
        if (pending == null) {
            // Not a WearFit frame start: pass it through untouched.
            if (packet.size < 3 || (packet.u8(0) != HEADER_AB && packet.u8(0) != HEADER_EA)) return packet.copyOf()
            val len = ((packet.u8(1) shl 8) or packet.u8(2)) + 3
            if (len > MAX_FRAME) return packet.copyOf()
            if (packet.size >= len) return packet.copyOfRange(0, len)
            buf = packet.copyOf()
            total = len
            return null
        }
        val joined = pending + packet.copyOfRange(minOf(1, packet.size), packet.size)
        if (joined.size >= total) {
            buf = null
            return joined.copyOfRange(0, total)
        }
        buf = joined
        return null
    }

    fun reset() {
        buf = null
    }

    private companion object {
        const val MAX_FRAME = 4096
    }
}
