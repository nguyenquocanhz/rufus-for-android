package io.github.nguyenquocanhz.rufusandroid.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Liệt kê USB Mass Storage đang cắm và xin quyền truy cập. */
object UsbDrives {
    private const val ACTION_USB_PERMISSION = "io.github.nguyenquocanhz.rufusandroid.USB_PERMISSION"

    fun list(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(UsbManager::class.java)
        return manager.deviceList.values.filter { ScsiBlockDevice.findMassStorageInterface(it) != null }
    }

    fun label(device: UsbDevice): String {
        val name = listOfNotNull(device.manufacturerNameSafe(), device.productNameSafe())
            .joinToString(" ").ifBlank { "USB %04X:%04X".format(device.vendorId, device.productId) }
        return name
    }

    suspend fun requestPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(UsbManager::class.java)
        if (manager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) {
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            // UsbManager điền EXTRA_DEVICE/EXTRA_PERMISSION_GRANTED vào intent nên phải là MUTABLE (Android 12+).
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags,
            )
            manager.requestPermission(device, pi)
        }
    }

    // Đọc tên cần quyền trên một số máy; lỗi thì bỏ qua.
    private fun UsbDevice.manufacturerNameSafe(): String? = runCatching { manufacturerName }.getOrNull()
    private fun UsbDevice.productNameSafe(): String? = runCatching { productName }.getOrNull()
}
