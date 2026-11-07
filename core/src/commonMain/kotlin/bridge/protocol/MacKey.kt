package bridge.protocol

/**
 * `input.key.keyCode` values: macOS virtual key codes (Carbon `kVK_*`), i.e. physical key positions on a US ANSI
 * keyboard. The Mac is the only receiver of `input.key`, so the wire uses its codes as they are (ADR 0010).
 */
object MacKey {
    const val RETURN = 0x24
    const val TAB = 0x30
    const val SPACE = 0x31
    const val DELETE = 0x33
    const val ESCAPE = 0x35
    const val HOME = 0x73
    const val PAGE_UP = 0x74
    const val FORWARD_DELETE = 0x75
    const val END = 0x77
    const val PAGE_DOWN = 0x79
    const val LEFT = 0x7B
    const val RIGHT = 0x7C
    const val DOWN = 0x7D
    const val UP = 0x7E
    const val EQUAL = 0x18
    const val MINUS = 0x1B

    /** Highest valid code. */
    const val MAX = 0x7F

    // The character at index i is what key code i types unshifted; NUL = none (0x0A is the ISO-only § key).
    private const val ANSI = "asdfhgzxcv\u0000bqweryt123465=97-80]ou[ip\nlj'k;\\,/nm.\t `"
    private const val SHIFTED = "~!@#$%^&*()_+{}|:\"<>?"
    private const val UNSHIFTED = "`1234567890-=[]\\;',./"

    /**
     * The key code and extra modifiers ([MOD_SHIFT]) that type [c] on a US layout, or null if no key does. For
     * shortcuts (⌘C), where `text` can't carry the modifier.
     */
    fun forChar(c: Char): Pair<Int, Int>? {
        val shifted = SHIFTED.indexOf(c)
        val (base, mods) = when {
            c in 'A'..'Z' -> c.lowercaseChar() to MOD_SHIFT
            shifted >= 0 -> UNSHIFTED[shifted] to MOD_SHIFT
            else -> c to 0
        }
        if (base == '\u0000') return null
        val code = ANSI.indexOf(base)
        return if (code < 0) null else code to mods
    }
}
