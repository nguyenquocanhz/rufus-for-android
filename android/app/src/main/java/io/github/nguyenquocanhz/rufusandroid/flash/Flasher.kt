package io.github.nguyenquocanhz.rufusandroid.flash

import io.github.nguyenquocanhz.rufusandroid.image.Compression
import io.github.nguyenquocanhz.rufusandroid.image.ImageInspector.fmtSize
import io.github.nguyenquocanhz.rufusandroid.image.ImageSource
import io.github.nguyenquocanhz.rufusandroid.usb.ScsiBlockDevice
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext

enum class Phase(val label: String) { PREPARE("Chuẩn bị"), WRITE("Đang ghi"), VERIFY("Đang kiểm tra"), DONE("Hoàn tất") }

data class FlashProgress(val phase: Phase, val done: Long, val total: Long, val bytesPerSec: Double) {
    val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

class FlashResult(val bytesWritten: Long, val sha256: String, val verified: Boolean)

/**
 * Ghi image kiểu DD (tương đương WriteDrive() trong src/format.c của Rufus) rồi đọc lại để kiểm tra.
 */
class Flasher(
    private val dev: ScsiBlockDevice,
    private val onProgress: (FlashProgress) -> Unit,
    private val log: (String) -> Unit,
) {
    private val chunk = (CHUNK_BYTES / dev.blockSize) * dev.blockSize

    suspend fun flash(source: ImageSource, verify: Boolean): FlashResult {
        onProgress(FlashProgress(Phase.PREPARE, 0, 0, 0.0))
        if (source.compression == Compression.NONE && source.fileSize > dev.capacity) {
            throw IOException("Image (${fmtSize(source.fileSize)}) lớn hơn dung lượng USB (${fmtSize(dev.capacity)})")
        }
        wipeTail()

        val crcs = ArrayList<Int>()
        val sha = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(chunk)
        var lba = 0L
        var written = 0L
        val opened = source.open()
        opened.entryName?.let { log("Giải nén từ zip: $it") }
        log("Bắt đầu ghi lên ${dev.vendor} ${dev.product} (${fmtSize(dev.capacity)}, sector ${dev.blockSize} B)")

        val speed = SpeedMeter()
        opened.stream.use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val n = ImageSource.readFully(input, buf, 0, chunk)
                if (n <= 0) break
                // Phần cuối không đủ sector thì đệm 0 (WriteFile của Rufus cũng yêu cầu bội số sector).
                val len = ((n + dev.blockSize - 1) / dev.blockSize) * dev.blockSize
                if (len > n) buf.fill(0, n, len)
                if (written + len > dev.capacity) throw IOException("Image lớn hơn dung lượng USB (${fmtSize(dev.capacity)})")

                writeWithRetry(lba, buf, len)
                sha.update(buf, 0, len)
                crcs += CRC32().apply { update(buf, 0, len) }.value.toInt()
                lba += len / dev.blockSize
                written += len

                val (done, total) = if (source.compression == Compression.NONE) written to source.fileSize
                    else opened.counter.count to source.fileSize
                onProgress(FlashProgress(Phase.WRITE, done, total, speed.update(written)))
                if (n < chunk) break
            }
        }
        dev.syncCache()
        val digest = sha.digest().joinToString("") { "%02x".format(it) }
        log("Đã ghi ${fmtSize(written)}. SHA-256 dữ liệu đã ghi: $digest")

        if (verify) verify(written, crcs)
        onProgress(FlashProgress(Phase.DONE, written, written, 0.0))
        return FlashResult(written, digest, verify)
    }

    private suspend fun verify(total: Long, crcs: List<Int>) {
        log("Đọc lại để kiểm tra...")
        val buf = ByteArray(chunk)
        val speed = SpeedMeter()
        var pos = 0L
        for ((i, expected) in crcs.withIndex()) {
            coroutineContext.ensureActive()
            val len = minOf(chunk.toLong(), total - pos).toInt()
            dev.read(pos / dev.blockSize, buf, 0, len)
            val crc = CRC32().apply { update(buf, 0, len) }.value.toInt()
            if (crc != expected) {
                throw IOException("Kiểm tra thất bại tại offset ${fmtSize(pos)} (khối #$i) — USB có thể bị lỗi hoặc giả dung lượng.")
            }
            pos += len
            onProgress(FlashProgress(Phase.VERIFY, pos, total, speed.update(pos)))
        }
        log("Kiểm tra OK: dữ liệu trên USB khớp với image.")
    }

    /**
     * Xoá 1 MiB cuối ổ: tránh GPT dự phòng cũ còn sót lại làm firmware/OS hiểu nhầm
     * bảng phân vùng (Rufus cũng xoá đầu và cuối ổ trước khi ghi).
     */
    private fun wipeTail() {
        val tailBlocks = minOf((1L shl 20) / dev.blockSize, dev.blockCount)
        val zero = ByteArray((tailBlocks * dev.blockSize).toInt())
        runCatching { dev.write(dev.blockCount - tailBlocks, zero, 0, zero.size) }
            .onFailure { log("Cảnh báo: không xoá được vùng cuối ổ (${it.message})") }
    }

    private fun writeWithRetry(lba: Long, buf: ByteArray, len: Int) {
        var last: Exception? = null
        for (attempt in 1..WRITE_RETRIES) {
            try {
                dev.write(lba, buf, 0, len)
                return
            } catch (e: IOException) {
                last = e
                log("Lỗi ghi tại sector $lba (lần $attempt/$WRITE_RETRIES): ${e.message}")
                Thread.sleep(500L * attempt)
            }
        }
        throw last ?: IOException("Ghi thất bại")
    }

    private class SpeedMeter {
        private val start = System.nanoTime()
        fun update(bytes: Long): Double {
            val sec = (System.nanoTime() - start) / 1e9
            return if (sec > 0.2) bytes / sec else 0.0
        }
    }

    companion object {
        private const val CHUNK_BYTES = 1 shl 20
        private const val WRITE_RETRIES = 4 // giống WRITE_RETRIES của Rufus
    }
}
