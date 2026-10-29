package io.github.nguyenquocanhz.rufusandroid.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbIoException(message: String) : IOException(message)

/**
 * Truy cập thô (raw) một USB flash drive qua USB Host API: giao thức
 * USB Mass Storage Bulk-Only Transport (BOT) + bộ lệnh SCSI tối thiểu.
 *
 * Tương đương việc Rufus mở `\\.\PhysicalDriveN` trên Windows, nhưng không cần root:
 * claimInterface(force = true) tách driver usb-storage của kernel khỏi thiết bị.
 */
class ScsiBlockDevice private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val lun: Int,
) : Closeable {

    var blockSize: Int = 512
        private set
    var blockCount: Long = 0
        private set
    val capacity: Long get() = blockSize.toLong() * blockCount

    var vendor: String = ""
        private set
    var product: String = ""
        private set

    private var tag = 1
    private val cbw = ByteBuffer.allocate(CBW_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private val csw = ByteArray(CSW_SIZE)
    private val maxBulk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 128 * 1024 else 16 * 1024

    private fun init() {
        inquiry()
        // Sau khi cắm, thiết bị thường báo UNIT ATTENTION vài lần đầu.
        var ready = false
        for (i in 0 until 10) {
            if (testUnitReady()) { ready = true; break }
            Thread.sleep(200)
        }
        if (!ready) throw UsbIoException("Thiết bị chưa sẵn sàng (TEST UNIT READY thất bại)")
        readCapacity()
    }

    private fun inquiry() {
        val data = ByteArray(36)
        command(byteArrayOf(0x12, 0, 0, 0, 36, 0), data, 0, data.size, dirIn = true)
        vendor = String(data, 8, 8, Charsets.US_ASCII).trim()
        product = String(data, 16, 16, Charsets.US_ASCII).trim()
    }

    private fun testUnitReady(): Boolean =
        try {
            command(ByteArray(6), null, 0, 0, dirIn = false)
            true
        } catch (e: UsbIoException) {
            false
        }

    private fun readCapacity() {
        val data = ByteArray(8)
        command(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), data, 0, 8, dirIn = true)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val lastLba = bb.int.toLong() and 0xFFFFFFFFL
        blockSize = bb.int
        if (lastLba == 0xFFFFFFFFL) {
            // > 2 TiB: READ CAPACITY(16)
            val d16 = ByteArray(32)
            val cb = ByteArray(16).also { it[0] = 0x9E.toByte(); it[1] = 0x10; it[13] = 32 }
            command(cb, d16, 0, 32, dirIn = true)
            val b16 = ByteBuffer.wrap(d16).order(ByteOrder.BIG_ENDIAN)
            blockCount = b16.long + 1
            blockSize = b16.int
        } else {
            blockCount = lastLba + 1
        }
        if (blockSize <= 0 || blockSize > 64 * 1024) throw UsbIoException("Kích thước sector không hợp lệ: $blockSize")
    }

    /** Ghi [length] byte (bội số của [blockSize]) từ [buf] vào sector [lba]. */
    fun write(lba: Long, buf: ByteArray, offset: Int, length: Int) {
        require(length % blockSize == 0)
        command(rwCommand(write = true, lba, length / blockSize), buf, offset, length, dirIn = false)
    }

    /** Đọc [length] byte (bội số của [blockSize]) từ sector [lba] vào [buf]. */
    fun read(lba: Long, buf: ByteArray, offset: Int, length: Int) {
        require(length % blockSize == 0)
        command(rwCommand(write = false, lba, length / blockSize), buf, offset, length, dirIn = true)
    }

    fun syncCache() {
        try {
            command(ByteArray(10).also { it[0] = 0x35 }, null, 0, 0, dirIn = false)
        } catch (_: UsbIoException) {
            // Không phải USB nào cũng hỗ trợ SYNCHRONIZE CACHE.
        }
    }

    private fun rwCommand(write: Boolean, lba: Long, blocks: Int): ByteArray =
        if (lba + blocks <= 0xFFFFFFFFL) {
            ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
                put(if (write) 0x2A else 0x28)          // WRITE(10) / READ(10)
                put(0)
                putInt(lba.toInt())
                put(0)
                putShort(blocks.toShort())
                put(0)
            }.array()
        } else {
            ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                put(if (write) 0x8A.toByte() else 0x88.toByte()) // WRITE(16) / READ(16)
                put(0)
                putLong(lba)
                putInt(blocks)
                put(0)
                put(0)
            }.array()
        }

    /** Một chu trình BOT: CBW → (dữ liệu) → CSW. */
    private fun command(cb: ByteArray, data: ByteArray?, offset: Int, length: Int, dirIn: Boolean) {
        val myTag = tag++
        cbw.clear()
        cbw.putInt(CBW_SIGNATURE)
        cbw.putInt(myTag)
        cbw.putInt(length)
        cbw.put(if (dirIn) 0x80.toByte() else 0)
        cbw.put(lun.toByte())
        cbw.put(cb.size.toByte())
        cbw.put(cb)
        while (cbw.position() < CBW_SIZE) cbw.put(0)
        if (bulk(epOut, cbw.array(), 0, CBW_SIZE) != CBW_SIZE) {
            resetRecovery()
            throw UsbIoException("Gửi lệnh SCSI 0x%02X thất bại".format(cb[0]))
        }

        if (data != null && length > 0) {
            var done = 0
            val ep = if (dirIn) epIn else epOut
            while (done < length) {
                val n = bulk(ep, data, offset + done, minOf(maxBulk, length - done))
                if (n < 0) {
                    clearHalt(ep)
                    break
                }
                done += n
                if (dirIn && n == 0) break
            }
        }

        var n = bulk(epIn, csw, 0, CSW_SIZE)
        if (n != CSW_SIZE) {
            clearHalt(epIn)
            n = bulk(epIn, csw, 0, CSW_SIZE)
        }
        if (n != CSW_SIZE) {
            resetRecovery()
            throw UsbIoException("Không đọc được trạng thái lệnh SCSI 0x%02X".format(cb[0]))
        }
        val b = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != CSW_SIGNATURE || b.int != myTag) {
            resetRecovery()
            throw UsbIoException("Trạng thái SCSI không hợp lệ")
        }
        b.int // residue
        when (b.get().toInt()) {
            0 -> return
            1 -> throw UsbIoException("Lệnh SCSI 0x%02X thất bại%s".format(cb[0], senseSuffix(cb[0])))
            else -> {
                resetRecovery()
                throw UsbIoException("Lỗi pha (phase error) ở lệnh SCSI 0x%02X".format(cb[0]))
            }
        }
    }

    private fun senseSuffix(op: Byte): String {
        if (op == 0x03.toByte()) return ""
        return try {
            val s = ByteArray(18)
            command(byteArrayOf(0x03, 0, 0, 0, 18, 0), s, 0, 18, dirIn = true)
            " (sense key %X, ASC %02X/%02X)".format(s[2].toInt() and 0x0F, s[12], s[13])
        } catch (_: UsbIoException) {
            ""
        }
    }

    private fun bulk(ep: UsbEndpoint, buf: ByteArray, off: Int, len: Int): Int =
        connection.bulkTransfer(ep, buf, off, len, TIMEOUT_MS)

    private fun clearHalt(ep: UsbEndpoint) {
        // CLEAR_FEATURE(ENDPOINT_HALT)
        connection.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, TIMEOUT_MS)
    }

    private fun resetRecovery() {
        // Bulk-Only Mass Storage Reset
        connection.controlTransfer(0x21, 0xFF, 0, iface.id, null, 0, TIMEOUT_MS)
        clearHalt(epIn)
        clearHalt(epOut)
    }

    override fun close() {
        connection.releaseInterface(iface)
        connection.close()
    }

    companion object {
        private const val CBW_SIGNATURE = 0x43425355
        private const val CSW_SIGNATURE = 0x53425355
        private const val CBW_SIZE = 31
        private const val CSW_SIZE = 13
        private const val TIMEOUT_MS = 20_000

        fun findMassStorageInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    it.interfaceSubclass == 6 && it.interfaceProtocol == 0x50
            }

        fun open(manager: UsbManager, device: UsbDevice): ScsiBlockDevice {
            val iface = findMassStorageInterface(device)
                ?: throw UsbIoException("Thiết bị không phải USB Mass Storage (SCSI/BOT)")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) throw UsbIoException("Không tìm thấy endpoint bulk")
            val conn = manager.openDevice(device) ?: throw UsbIoException("Không mở được thiết bị (chưa cấp quyền?)")
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw UsbIoException("Không chiếm được interface USB")
            }
            val dev = ScsiBlockDevice(conn, iface, epIn, epOut, lun = 0)
            try {
                dev.init()
            } catch (e: Exception) {
                dev.close()
                throw e
            }
            return dev
        }
    }
}
