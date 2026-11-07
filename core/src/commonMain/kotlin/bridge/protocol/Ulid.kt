package bridge.protocol

import kotlin.random.Random

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** 26-char ULID: 48-bit millis then 80 random bits, Crockford base32. Not secret, so kotlin.random is fine. */
fun newUlid(nowMillis: Long): String {
    val out = CharArray(26)
    var t = nowMillis
    for (i in 9 downTo 0) {
        out[i] = CROCKFORD[(t and 31).toInt()]
        t = t shr 5
    }
    var acc = 0
    var bits = 0
    var j = 10
    for (b in Random.nextBytes(10)) {
        acc = (acc shl 8) or (b.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            bits -= 5
            out[j++] = CROCKFORD[(acc shr bits) and 31]
        }
    }
    return out.concatToString()
}
