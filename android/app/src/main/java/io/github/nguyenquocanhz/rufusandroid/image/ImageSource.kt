package io.github.nguyenquocanhz.rufusandroid.image

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

enum class Compression(val label: String, val supported: Boolean) {
    NONE("Không nén", true),
    GZIP("gzip (.gz)", true),
    XZ("xz (.xz)", true),
    ZIP("zip", true),
    ZSTD("zstd (.zst)", false),
    BZIP2("bzip2 (.bz2)", false),
}

/** Đếm số byte đã đọc từ file gốc (đã nén) để tính tiến độ. */
class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }
    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}

class OpenedImage(val stream: InputStream, val counter: CountingInputStream, val entryName: String?)

/**
 * File image người dùng chọn qua Storage Access Framework.
 * Giống bled trong Rufus: giải nén on-the-fly khi ghi, không cần giải nén ra bộ nhớ trước.
 */
class ImageSource(private val context: Context, val uri: Uri) {
    val name: String
    /** Kích thước file trên bộ nhớ (đã nén nếu là file nén). */
    val fileSize: Long
    val compression: Compression

    init {
        var n = uri.lastPathSegment ?: "image"
        var s = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { n = it }
                    if (!c.isNull(1)) s = c.getLong(1)
                }
            }
        name = n
        fileSize = s
        compression = detectCompression()
    }

    private fun detectCompression(): Compression {
        val head = ByteArray(6)
        val n = context.contentResolver.openInputStream(uri)?.use { readFully(it, head, 0, 6) } ?: 0
        if (n < 4) return Compression.NONE
        fun u(i: Int) = head[i].toInt() and 0xFF
        return when {
            u(0) == 0x1F && u(1) == 0x8B -> Compression.GZIP
            n >= 6 && u(0) == 0xFD && head[1] == '7'.code.toByte() && head[2] == 'z'.code.toByte() &&
                head[3] == 'X'.code.toByte() && head[4] == 'Z'.code.toByte() && u(5) == 0 -> Compression.XZ
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && u(2) == 3 && u(3) == 4 -> Compression.ZIP
            u(0) == 0x28 && u(1) == 0xB5 && u(2) == 0x2F && u(3) == 0xFD -> Compression.ZSTD
            head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() && head[2] == 'h'.code.toByte() -> Compression.BZIP2
            else -> Compression.NONE
        }
    }

    /** Liệt kê tên file trong zip (để nhận diện gói OTA/factory image của điện thoại). */
    fun zipEntries(): List<String> {
        if (compression != Compression.ZIP) return emptyList()
        val names = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    names += e.name
                    if (names.size > 500) break
                }
            }
        }
        return names
    }

    /** Mở luồng dữ liệu image đã giải nén. */
    fun open(): OpenedImage {
        val raw = context.contentResolver.openInputStream(uri) ?: throw IOException("Không mở được $name")
        val counter = CountingInputStream(raw)
        val buffered = BufferedInputStream(counter, 1 shl 20)
        return when (compression) {
            Compression.NONE -> OpenedImage(buffered, counter, null)
            Compression.GZIP -> OpenedImage(GZIPInputStream(buffered, 1 shl 16), counter, null)
            Compression.XZ -> OpenedImage(XZInputStream(buffered), counter, null)
            Compression.ZIP -> {
                val zip = ZipInputStream(buffered)
                while (true) {
                    val e = zip.nextEntry ?: run {
                        zip.close()
                        throw IOException("Trong file zip không có image (.img/.iso/.raw)")
                    }
                    if (!e.isDirectory && isImageName(e.name)) return OpenedImage(zip, counter, e.name)
                }
                @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
            }
            else -> {
                raw.close()
                throw IOException("Chưa hỗ trợ định dạng nén ${compression.label}")
            }
        }
    }

    /**
     * Đọc ngẫu nhiên trên file không nén (nhanh, không giới hạn vị trí).
     * Trả về null nếu provider không cho mở file descriptor.
     */
    fun openRandomAccess(): RandomReader? {
        if (compression != Compression.NONE) return null
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        val channel = FileInputStream(pfd.fileDescriptor).channel
        return object : RandomReader {
            override val size: Long = runCatching { channel.size() }.getOrDefault(fileSize)
            override fun read(pos: Long, len: Int): ByteArray? {
                if (pos < 0 || pos + len > size) return null
                val bb = ByteBuffer.allocate(len)
                var p = pos
                while (bb.hasRemaining()) {
                    val n = channel.read(bb, p)
                    if (n <= 0) return null
                    p += n
                }
                return bb.array()
            }
            override fun close() {
                channel.close()
                pfd.close()
            }
        }
    }

    /** Đọc [limit] byte đầu của image đã giải nén vào bộ nhớ để phân tích. */
    fun readHead(limit: Int): RandomReader {
        val opened = open()
        val buf = ByteArray(limit)
        val n = opened.stream.use { readFully(it, buf, 0, limit) }
        return object : RandomReader {
            override val size: Long = n.toLong()
            override fun read(pos: Long, len: Int): ByteArray? =
                if (pos < 0 || pos + len > n) null else buf.copyOfRange(pos.toInt(), pos.toInt() + len)
            override fun close() {}
        }
    }

    companion object {
        fun isImageName(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".img") || n.endsWith(".iso") || n.endsWith(".raw") || n.endsWith(".bin")
        }

        fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
            var done = 0
            while (done < len) {
                val n = input.read(buf, off + done, len - done)
                if (n < 0) break
                done += n
            }
            return done
        }
    }
}

interface RandomReader : java.io.Closeable {
    val size: Long
    /** Đọc [len] byte tại [pos]; null nếu vượt quá vùng đọc được. */
    fun read(pos: Long, len: Int): ByteArray?
}
