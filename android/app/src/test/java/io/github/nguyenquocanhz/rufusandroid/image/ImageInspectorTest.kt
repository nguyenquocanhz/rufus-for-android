package io.github.nguyenquocanhz.rufusandroid.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImageInspectorTest {

    private fun reader(data: ByteArray) = object : RandomReader {
        override val size = data.size.toLong()
        override fun read(pos: Long, len: Int) =
            if (pos < 0 || pos + len > data.size) null else data.copyOfRange(pos.toInt(), pos.toInt() + len)
        override fun close() {}
    }

    private fun le32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte()
    }

    /** Ghi một bản ghi thư mục ISO9660, trả về vị trí kế tiếp. */
    private fun dirRecord(b: ByteArray, pos: Int, name: String, extent: Long, size: Long, dir: Boolean): Int {
        val nameBytes = if (name == ".") byteArrayOf(0) else if (name == "..") byteArrayOf(1) else name.toByteArray()
        var len = 33 + nameBytes.size
        if (len % 2 == 1) len++
        b[pos] = len.toByte()
        le32(b, pos + 2, extent)
        le32(b, pos + 10, size)
        b[pos + 25] = if (dir) 2 else 0
        b[pos + 32] = nameBytes.size.toByte()
        nameBytes.copyInto(b, pos + 33)
        return pos + len
    }

    private fun androidX86Iso(hybrid: Boolean): ByteArray {
        val s = 2048
        val b = ByteArray(40 * s)
        if (hybrid) { b[510] = 0x55; b[511] = 0xAA.toByte() }
        // PVD (sector 16) + terminator (17)
        b[16 * s] = 1; "CD001".toByteArray().copyInto(b, 16 * s + 1)
        "Android-x86 LiveCD".padEnd(32).toByteArray().copyInto(b, 16 * s + 40)
        dirRecord(b, 16 * s + 156, ".", 20, s.toLong(), true)
        b[17 * s] = 0xFF.toByte(); "CD001".toByteArray().copyInto(b, 17 * s + 1)
        // Thư mục gốc (sector 20)
        var p = 20 * s
        p = dirRecord(b, p, ".", 20, s.toLong(), true)
        p = dirRecord(b, p, "..", 20, s.toLong(), true)
        p = dirRecord(b, p, "EFI", 21, s.toLong(), true)
        p = dirRecord(b, p, "ISOLINUX", 25, s.toLong(), true)
        p = dirRecord(b, p, "KERNEL;1", 30, 4096, false)
        p = dirRecord(b, p, "RAMDISK.IMG;1", 32, 100, false)
        dirRecord(b, p, "SYSTEM.SFS;1", 33, 100, false)
        // /EFI (21) → /EFI/BOOT (22) → BOOTX64.EFI
        p = dirRecord(b, 21 * s, ".", 21, s.toLong(), true)
        p = dirRecord(b, p, "..", 20, s.toLong(), true)
        dirRecord(b, p, "BOOT", 22, s.toLong(), true)
        p = dirRecord(b, 22 * s, ".", 22, s.toLong(), true)
        p = dirRecord(b, p, "..", 21, s.toLong(), true)
        dirRecord(b, p, "BOOTX64.EFI;1", 34, 100, false)
        // Kernel x86 bzImage 64-bit
        "HdrS".toByteArray().copyInto(b, 30 * s + 0x202)
        b[30 * s + 0x236] = 1
        return b
    }

    @Test
    fun detectsAndroidX86HybridIso() {
        val r = ImageInspector.analyze(reader(androidX86Iso(hybrid = true)), Compression.NONE, null)
        assertEquals(ImageKind.ANDROID_X86_ISO, r.kind)
        assertEquals("Android-x86", r.android)
        assertTrue(r.arch.toString(), r.arch.contains("x86_64 (UEFI)"))
        assertTrue(r.arch.toString(), r.arch.contains("x86_64 (kernel)"))
        assertTrue(r.arch.toString(), r.arch.contains("BIOS Legacy (isolinux)"))
        assertTrue(r.writable)
    }

    @Test
    fun blocksNonHybridIso() {
        val r = ImageInspector.analyze(reader(androidX86Iso(hybrid = false)), Compression.NONE, null)
        assertEquals(ImageKind.ISO_PLAIN, r.kind)
        assertFalse(r.writable)
    }

    @Test
    fun blocksAndroidSparseImage() {
        val b = ByteArray(4096)
        le32(b, 0, 0xED26FF3AL)
        val r = ImageInspector.analyze(reader(b), Compression.NONE, null)
        assertEquals(ImageKind.ANDROID_SPARSE, r.kind)
        assertFalse(r.writable)
    }

    @Test
    fun detectsAndroidGptOnRockchip() {
        val b = ByteArray(1 shl 20)
        b[510] = 0x55; b[511] = 0xAA.toByte()
        "EFI PART".toByteArray().copyInto(b, 512)
        val h = ByteBuffer.wrap(b, 512, 92).order(ByteOrder.LITTLE_ENDIAN)
        h.putLong(512 + 72, 2); h.putInt(512 + 80, 4); h.putInt(512 + 84, 128)
        listOf("boot_a", "super", "userdata", "misc").forEachIndexed { i, name ->
            val o = 1024 + i * 128
            b[o] = 1 // type GUID khác 0
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(o + 32, 2048L * (i + 1)).putLong(o + 40, 2048L * (i + 2) - 1)
            name.toByteArray(Charsets.UTF_16LE).copyInto(b, o + 56)
        }
        "RKNS".toByteArray().copyInto(b, 64 * 512)
        val r = ImageInspector.analyze(reader(b), Compression.NONE, null)
        assertEquals(ImageKind.DISK_GPT, r.kind)
        assertEquals("Rockchip", r.board)
        assertEquals("Android cho Rockchip", r.android)
        assertEquals(4, r.partitions.size)
    }
}
