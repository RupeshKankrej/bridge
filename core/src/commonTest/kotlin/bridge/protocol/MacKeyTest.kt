package bridge.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacKeyTest {
    @Test
    fun mapsCharactersToUsAnsiKeysWithShift() {
        assertEquals(0x08 to 0, MacKey.forChar('c')) // kVK_ANSI_C
        assertEquals(0x08 to MOD_SHIFT, MacKey.forChar('C'))
        assertEquals(0x2C to MOD_SHIFT, MacKey.forChar('?')) // ⇧/
        assertEquals(0x1D to 0, MacKey.forChar('0'))
        assertEquals(0x32 to MOD_SHIFT, MacKey.forChar('~'))
        assertEquals(MacKey.RETURN to 0, MacKey.forChar('\n'))
        assertEquals(MacKey.TAB to 0, MacKey.forChar('\t'))
        assertEquals(MacKey.SPACE to 0, MacKey.forChar(' '))
        assertEquals(MacKey.EQUAL to 0, MacKey.forChar('='))
        assertNull(MacKey.forChar('é'))
        assertNull(MacKey.forChar('\u0000'))
    }
}
