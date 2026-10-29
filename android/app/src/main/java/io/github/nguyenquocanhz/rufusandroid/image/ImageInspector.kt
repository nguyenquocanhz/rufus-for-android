package io.github.nguyenquocanhz.rufusandroid.image

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ImageKind(val label: String) {
    ANDROID_X86_ISO("Android x86 (ISOHybrid)"),
    ISO_HYBRID("ISOHybrid (ghi DD được)"),
    ISO_PLAIN("ISO thường (không hybrid)"),
    DISK_GPT("Disk image (GPT)"),
    DISK_MBR("Disk image (MBR)"),
    ANDROID_SPARSE("Android sparse image (fastboot)"),
    ANDROID_BOOT("Android boot.img"),
    ANDROID_OTA("Gói OTA / factory image điện thoại"),
    UNKNOWN("Không xác định"),
}

data class ImageReport(
    val kind: ImageKind,
    val compression: Compression,
    val innerName: String? = null,
    val volumeLabel: String? = null,
    /** Phát hiện bản phân phối Android, vd "Android-x86", "Bliss OS", "Android (ARM SBC)". */
    val android: String? = null,
    val arch: List<String> = emptyList(),
    val board: String? = null,
    val partitions: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** false = không cho ghi (vô nghĩa hoặc chắc chắn không boot). */
    val writable: Boolean = true,
    val blockReason: String? = null,
)

/**
 * Phân tích image trước khi ghi — mở rộng so với IsBootableImage() của Rufus
 * (chỉ kiểm tra 0x55AA) để nhận diện Android x86 và Android ARM.
 */
object ImageInspector {
    private const val ISO_SECTOR = 2048
    private const val HEAD_LIMIT = 24 shl 20 // đủ để chạm tới phân vùng boot đầu tiên (thường ở 4–16 MiB)

    fun inspect(source: ImageSource): ImageReport {
        val comp = source.compression
        if (!comp.supported) {
            return ImageReport(ImageKind.UNKNOWN, comp, writable = false,
                blockReason = "Chưa hỗ trợ ${comp.label}. Hãy giải nén ra .img/.iso trước.")
        }
        if (comp == Compression.ZIP) {
            val entries = source.zipEntries().map { it.lowercase() }
            if (entries.any { it.endsWith("payload.bin") } ||
                (entries.any { it.endsWith("android-info.txt") } && entries.none { it.endsWith(".iso") })
            ) {
                return ImageReport(ImageKind.ANDROID_OTA, comp, writable = false,
                    blockReason = "Đây là gói cập nhật/factory image cho điện thoại. Không boot được từ USB — " +
                        "hãy flash bằng fastboot hoặc công cụ của hãng.")
            }
        }
        val reader = source.openRandomAccess() ?: source.readHead(HEAD_LIMIT)
        return reader.use { analyze(it, comp, null) }.let {
            if (comp == Compression.ZIP) it.copy(innerName = runCatching { source.open().apply { stream.close() }.entryName }.getOrNull()) else it
        }
    }

    internal fun analyze(r: RandomReader, comp: Compression, inner: String?): ImageReport {
        val s0 = r.read(0, 512) ?: return ImageReport(ImageKind.UNKNOWN, comp, inner, writable = false,
            blockReason = "File quá nhỏ hoặc không đọc được.")

        // --- Định dạng riêng của điện thoại Android ---
        if (u32(s0, 0) == 0xED26FF3AL) {
            return ImageReport(ImageKind.ANDROID_SPARSE, comp, inner, writable = false,
                blockReason = "Android sparse image (system/vendor/super.img) dùng để flash qua fastboot, " +
                    "không phải image boot từ USB.")
        }
        if (String(s0, 0, 8, Charsets.US_ASCII) == "ANDROID!") {
            return ImageReport(ImageKind.ANDROID_BOOT, comp, inner, writable = false,
                blockReason = "boot.img của điện thoại — chỉ flash qua fastboot, không boot được từ USB.")
        }

        val hasMbrSig = (s0[510].toInt() and 0xFF) == 0x55 && (s0[511].toInt() and 0xFF) == 0xAA
        val iso = parseIso(r)

        if (iso != null) {
            val arch = mutableListOf<String>()
            iso.efiFiles.forEach { f ->
                when (f) {
                    "bootx64.efi" -> arch += "x86_64 (UEFI)"
                    "bootia32.efi" -> arch += "x86 32-bit (UEFI)"
                    "bootaa64.efi" -> arch += "ARM64 (UEFI)"
                    "bootarm.efi" -> arch += "ARM 32-bit (UEFI)"
                    "bootriscv64.efi" -> arch += "RISC-V 64 (UEFI)"
                }
            }
            iso.kernelArch?.let { if (it !in arch) arch += it }
            if (iso.hasIsolinux) arch += "BIOS Legacy (isolinux)"

            val android = detectAndroidIso(iso)
            val notes = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val kind = when {
                !hasMbrSig -> ImageKind.ISO_PLAIN
                android != null -> ImageKind.ANDROID_X86_ISO
                else -> ImageKind.ISO_HYBRID
            }
            if (android != null) {
                val sys = iso.rootFiles.firstOrNull { it.startsWith("system.") }
                notes += "Phát hiện hệ thống Android: kernel, ${sys ?: "system"}, " +
                    (if ("ramdisk.img" in iso.rootFiles) "ramdisk.img" else "initrd.img")
                notes += "Ghi dạng DD → chạy Live, dữ liệu không được lưu sau khi tắt máy (chưa hỗ trợ persistence)."
                if (iso.efiFiles.isEmpty() && iso.kernelArch == null) warnings += "Không xác định được kiến trúc."
            }
            if (kind == ImageKind.ISO_PLAIN) {
                return ImageReport(kind, comp, inner, iso.volumeId, android, arch, writable = false,
                    blockReason = "ISO này không phải ISOHybrid nên ghi dạng DD sẽ không boot được. " +
                        "Cần chế độ ISO (format FAT32 + chép file) — chưa có trong bản này.")
            }
            return ImageReport(kind, comp, inner, iso.volumeId, android, arch,
                partitions = parseMbr(s0), notes = notes, warnings = warnings)
        }

        // --- Disk image thô (thường là ARM SBC hoặc image cài sẵn) ---
        val gpt = parseGpt(r)
        val parts = if (gpt != null) gpt.map { "${it.name.ifBlank { "(không tên)" }} — ${fmtSize(it.size)}" } else parseMbr(s0)
        val partNames = gpt?.map { it.name.lowercase() } ?: emptyList()
        val androidParts = setOf("system", "system_a", "vendor", "vendor_a", "super", "userdata", "vbmeta", "vbmeta_a", "boot_a", "misc", "metadata")
        val androidLayout = partNames.count { it in androidParts } >= 2

        val board = detectBoard(r, s0)
        val notes = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val arch = mutableListOf<String>()
        if (board != null) arch += "ARM"
        if (androidLayout) notes += "Bảng phân vùng kiểu Android: " + partNames.filter { it in androidParts }.joinToString()
        val android = when {
            androidLayout && board != null -> "Android cho $board"
            androidLayout -> "Android (bảng phân vùng)"
            else -> null
        }
        if (!hasMbrSig && gpt == null) {
            warnings += "Không thấy chữ ký boot 0x55AA ở sector 0 — image có thể không boot được (Rufus cũng cảnh báo trường hợp này)."
        }
        if (board == null && android == null && gpt == null && !hasMbrSig) {
            return ImageReport(ImageKind.UNKNOWN, comp, inner, android = null, partitions = parts, warnings = warnings)
        }
        return ImageReport(if (gpt != null) ImageKind.DISK_GPT else ImageKind.DISK_MBR, comp, inner,
            android = android, arch = arch, board = board, partitions = parts, notes = notes, warnings = warnings)
    }

    // ---------------------------------------------------------------- ISO9660

    private class IsoInfo(
        val volumeId: String,
        val rootFiles: Set<String>,
        val efiFiles: Set<String>,
        val hasIsolinux: Boolean,
        val kernelArch: String?,
    )

    private data class DirEntry(val name: String, val extent: Long, val size: Long, val isDir: Boolean)

    private fun parseIso(r: RandomReader): IsoInfo? {
        var pvd: ByteArray? = null
        for (sector in 16 until 32) {
            val d = r.read(sector.toLong() * ISO_SECTOR, ISO_SECTOR) ?: return null
            if (String(d, 1, 5, Charsets.US_ASCII) != "CD001") return null
            val type = d[0].toInt() and 0xFF
            if (type == 1) { pvd = d; break }
            if (type == 255) break
        }
        val p = pvd ?: return null
        val volumeId = String(p, 40, 32, Charsets.US_ASCII).trim()
        val rootExtent = u32(p, 156 + 2)
        val rootSize = u32(p, 156 + 10)
        val root = readDir(r, rootExtent, rootSize) ?: return IsoInfo(volumeId, emptySet(), emptySet(), false, null)

        val efiFiles = mutableSetOf<String>()
        root.firstOrNull { it.isDir && it.name == "efi" }?.let { efi ->
            readDir(r, efi.extent, efi.size)?.firstOrNull { it.isDir && it.name == "boot" }?.let { boot ->
                readDir(r, boot.extent, boot.size)?.forEach { if (!it.isDir) efiFiles += it.name }
            }
        }
        val kernelArch = root.firstOrNull { !it.isDir && it.name in setOf("kernel", "vmlinuz", "bzimage") }
            ?.let { r.read(it.extent * ISO_SECTOR, 0x240) }
            ?.let { kernelArch(it) }
        return IsoInfo(
            volumeId,
            root.filter { !it.isDir }.map { it.name }.toSet(),
            efiFiles,
            root.any { it.isDir && (it.name == "isolinux" || it.name == "syslinux") },
            kernelArch,
        )
    }

    private fun readDir(r: RandomReader, extent: Long, size: Long): List<DirEntry>? {
        if (size <= 0 || size > (1 shl 20)) return null
        val data = r.read(extent * ISO_SECTOR, size.toInt()) ?: return null
        val out = mutableListOf<DirEntry>()
        var pos = 0
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) {
                pos = (pos / ISO_SECTOR + 1) * ISO_SECTOR // bản ghi không vắt qua ranh giới sector
                continue
            }
            if (pos + 33 > data.size) break
            val nameLen = data[pos + 32].toInt() and 0xFF
            if (nameLen > 0 && pos + 33 + nameLen <= data.size) {
                val first = data[pos + 33].toInt()
                if (!(nameLen == 1 && (first == 0 || first == 1))) { // bỏ "." và ".."
                    val name = String(data, pos + 33, nameLen, Charsets.US_ASCII)
                        .substringBefore(';').trimEnd('.').lowercase()
                    val isDir = (data[pos + 25].toInt() and 0x02) != 0
                    out += DirEntry(name, u32(data, pos + 2), u32(data, pos + 10), isDir)
                }
            }
            pos += len
        }
        return out
    }

    private fun detectAndroidIso(iso: IsoInfo): String? {
        val f = iso.rootFiles
        val hasSystem = f.any { it == "system.sfs" || it == "system.efs" || it == "system.img" }
        val hasRamdisk = "ramdisk.img" in f || "initrd.img" in f
        if (!(hasSystem && hasRamdisk && "kernel" in f)) return null
        val v = iso.volumeId.lowercase()
        return when {
            "bliss" in v -> "Bliss OS"
            "prime" in v -> "PrimeOS"
            "phoenix" in v -> "Phoenix OS"
            "lineage" in v -> "LineageOS x86"
            else -> "Android-x86"
        }
    }

    /** Nhận diện kiến trúc từ header kernel (bzImage x86 hoặc Image ARM64). */
    private fun kernelArch(k: ByteArray): String? {
        if (k.size >= 0x240 && String(k, 0x202, 4, Charsets.US_ASCII) == "HdrS") {
            val xloadflags = (k[0x236].toInt() and 0xFF) or ((k[0x237].toInt() and 0xFF) shl 8)
            return if (xloadflags and 1 != 0) "x86_64 (kernel)" else "x86 32-bit (kernel)"
        }
        if (k.size >= 0x3C && String(k, 0x38, 4, Charsets.ISO_8859_1) == "ARMd") return "ARM64 (kernel)"
        return null
    }

    // ---------------------------------------------------------------- Bảng phân vùng

    private fun parseMbr(s0: ByteArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until 4) {
            val e = 446 + i * 16
            val type = s0[e + 4].toInt() and 0xFF
            val sectors = u32(s0, e + 12)
            if (type == 0 || sectors == 0L) continue
            out += "#${i + 1} ${mbrTypeName(type)} — ${fmtSize(sectors * 512)}"
        }
        return out
    }

    private fun mbrTypeName(t: Int) = when (t) {
        0x01, 0x04, 0x06, 0x0B, 0x0C, 0x0E -> "FAT"
        0x07 -> "NTFS/exFAT"
        0x0F, 0x05 -> "Extended"
        0x17 -> "Hidden NTFS"
        0x82 -> "Linux swap"
        0x83 -> "Linux"
        0xEE -> "GPT protective"
        0xEF -> "EFI System"
        0xDA -> "Non-FS data"
        else -> "Type 0x%02X".format(t)
    }

    private class GptPart(val name: String, val first: Long, val size: Long)

    private fun parseGpt(r: RandomReader): List<GptPart>? {
        for (sectorSize in intArrayOf(512, 4096)) {
            val h = r.read(sectorSize.toLong(), 92) ?: continue
            if (String(h, 0, 8, Charsets.US_ASCII) != "EFI PART") continue
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val entryLba = bb.getLong(72)
            val count = bb.getInt(80).coerceIn(0, 256)
            val esize = bb.getInt(84)
            if (esize < 128 || esize > 1024) return null
            val table = r.read(entryLba * sectorSize, count * esize) ?: return emptyList()
            val tb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
            val out = mutableListOf<GptPart>()
            for (i in 0 until count) {
                val o = i * esize
                if ((0 until 16).all { table[o + it].toInt() == 0 }) continue
                val first = tb.getLong(o + 32)
                val last = tb.getLong(o + 40)
                val name = String(table, o + 56, 72, Charsets.UTF_16LE).substringBefore('\u0000')
                out += GptPart(name, first, (last - first + 1) * sectorSize)
            }
            return out
        }
        return null
    }

    // ---------------------------------------------------------------- Bo mạch ARM

    private fun detectBoard(r: RandomReader, s0: ByteArray): String? {
        // Allwinner: SPL "eGON.BT0" ở offset 8 KiB (+4)
        r.read(8192 + 4, 8)?.let { if (String(it, Charsets.US_ASCII) == "eGON.BT0") return "Allwinner" }
        // Rockchip: idbloader ở sector 64; định dạng mới có magic "RKNS"
        r.read(64L * 512, 4)?.let { if (String(it, Charsets.US_ASCII) == "RKNS") return "Rockchip" }
        // Raspberry Pi: phân vùng FAT đầu tiên chứa firmware start*.elf / bootcode.bin
        for (i in 0 until 4) {
            val e = 446 + i * 16
            val type = s0[e + 4].toInt() and 0xFF
            if (type !in setOf(0x0B, 0x0C, 0x0E, 0x06)) continue
            val names = fatRootNames(r, u32(s0, e + 8) * 512) ?: continue
            if (names.any { it == "START4.ELF" || it == "START.ELF" || it == "BOOTCODE.BIN" }) {
                return if ("START4.ELF" in names || names.any { it.startsWith("BCM2711") || it.startsWith("BCM2712") })
                    "Raspberry Pi 4/5" else "Raspberry Pi"
            }
            if (names.any { it.startsWith("BCM27") } && "CONFIG.TXT" in names) return "Raspberry Pi"
        }
        return null
    }

    /** Liệt kê tên 8.3 ở thư mục gốc của một phân vùng FAT12/16/32. */
    private fun fatRootNames(r: RandomReader, partOffset: Long): List<String>? {
        val bs = r.read(partOffset, 512) ?: return null
        if ((bs[510].toInt() and 0xFF) != 0x55) return null
        val bb = ByteBuffer.wrap(bs).order(ByteOrder.LITTLE_ENDIAN)
        val bps = bb.getShort(11).toInt() and 0xFFFF
        val spc = bs[13].toInt() and 0xFF
        val reserved = bb.getShort(14).toInt() and 0xFFFF
        val fats = bs[16].toInt() and 0xFF
        val rootEnt = bb.getShort(17).toInt() and 0xFFFF
        val fatSz16 = bb.getShort(22).toInt() and 0xFFFF
        if (bps !in setOf(512, 1024, 2048, 4096) || spc == 0 || fats == 0) return null
        val fatSz = if (fatSz16 != 0) fatSz16.toLong() else (bb.getInt(36).toLong() and 0xFFFFFFFFL)
        val (rootOff, rootLen) = if (rootEnt != 0) {
            (reserved + fats * fatSz) * bps to rootEnt * 32
        } else {
            val rootClus = bb.getInt(44).toLong() and 0xFFFFFFFFL
            (reserved + fats * fatSz + (rootClus - 2) * spc) * bps to minOf(spc * bps * 4, 64 * 1024)
        }
        val dir = r.read(partOffset + rootOff, rootLen) ?: r.read(partOffset + rootOff, spc * bps) ?: return null
        val names = mutableListOf<String>()
        for (o in 0 until dir.size - 31 step 32) {
            val first = dir[o].toInt() and 0xFF
            if (first == 0) break
            if (first == 0xE5 || (dir[o + 11].toInt() and 0xFF) == 0x0F) continue
            val base = String(dir, o, 8, Charsets.US_ASCII).trim()
            val ext = String(dir, o + 8, 3, Charsets.US_ASCII).trim()
            names += if (ext.isEmpty()) base else "$base.$ext"
        }
        return names
    }

    // ---------------------------------------------------------------- Tiện ích

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24))

    fun fmtSize(bytes: Long): String {
        if (bytes < 0) return "?"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$bytes B" else "%.1f %s".format(v, units[i])
    }
}
