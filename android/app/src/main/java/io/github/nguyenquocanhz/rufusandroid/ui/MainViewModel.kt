package io.github.nguyenquocanhz.rufusandroid.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nguyenquocanhz.rufusandroid.flash.FlashProgress
import io.github.nguyenquocanhz.rufusandroid.flash.Flasher
import io.github.nguyenquocanhz.rufusandroid.image.ImageInspector
import io.github.nguyenquocanhz.rufusandroid.image.ImageReport
import io.github.nguyenquocanhz.rufusandroid.image.ImageSource
import io.github.nguyenquocanhz.rufusandroid.usb.ScsiBlockDevice
import io.github.nguyenquocanhz.rufusandroid.usb.UsbDrives
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DriveInfo(val device: UsbDevice, val label: String, val capacity: Long? = null, val detail: String? = null)

data class UiState(
    val drives: List<DriveInfo> = emptyList(),
    val selectedDrive: DriveInfo? = null,
    val imageName: String? = null,
    val imageSize: Long = -1,
    val report: ImageReport? = null,
    val analyzing: Boolean = false,
    val verify: Boolean = true,
    val busy: Boolean = false,
    val progress: FlashProgress? = null,
    val status: String = "Sẵn sàng",
    val log: List<String> = emptyList(),
) {
    val canStart: Boolean
        get() = !busy && !analyzing && selectedDrive?.capacity != null && report?.writable == true
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var source: ImageSource? = null
    private var job: Job? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        refreshDrives()
    }

    fun log(msg: String) = _state.update { it.copy(log = (it.log + "[${timeFmt.format(Date())}] $msg").takeLast(300)) }

    fun refreshDrives() {
        if (_state.value.busy) return
        val list = UsbDrives.list(getApplication()).map { DriveInfo(it, UsbDrives.label(it)) }
        _state.update { s ->
            val keep = s.selectedDrive?.takeIf { sel -> list.any { it.device.deviceName == sel.device.deviceName } }
            s.copy(drives = list, selectedDrive = keep)
        }
        if (list.isEmpty()) log("Chưa thấy USB nào. Cắm USB qua cáp OTG rồi bấm Làm mới.")
    }

    fun onDeviceDetached(device: UsbDevice) {
        if (_state.value.selectedDrive?.device?.deviceName == device.deviceName && _state.value.busy) {
            job?.cancel()
            log("USB đã bị rút ra khi đang ghi!")
        }
        refreshDrives()
    }

    fun selectDrive(drive: DriveInfo) {
        if (_state.value.busy) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!UsbDrives.requestPermission(ctx, drive.device)) {
                log("Không được cấp quyền truy cập ${drive.label}")
                return@launch
            }
            _state.update { it.copy(status = "Đang đọc thông tin USB...") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ScsiBlockDevice.open(ctx.getSystemService(UsbManager::class.java), drive.device).use {
                        Triple(it.capacity, "${it.vendor} ${it.product}".trim(), it.blockSize)
                    }
                }
            }
            result.onSuccess { (cap, name, bs) ->
                val info = drive.copy(capacity = cap, detail = "$name • ${ImageInspector.fmtSize(cap)} • sector $bs B")
                _state.update { s ->
                    s.copy(selectedDrive = info, status = "Sẵn sàng",
                        drives = s.drives.map { if (it.device.deviceName == info.device.deviceName) info else it })
                }
                log("Đã chọn USB: ${info.detail}")
            }.onFailure {
                _state.update { s -> s.copy(status = "Lỗi USB") }
                log("Không đọc được USB: ${it.message}")
            }
        }
    }

    fun selectImage(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(analyzing = true, report = null, status = "Đang phân tích image...") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val src = ImageSource(getApplication(), uri)
                    src to ImageInspector.inspect(src)
                }
            }
            result.onSuccess { (src, report) ->
                source = src
                _state.update {
                    it.copy(analyzing = false, imageName = src.name, imageSize = src.fileSize, report = report, status = "Sẵn sàng")
                }
                log("Image: ${src.name} (${ImageInspector.fmtSize(src.fileSize)}) → ${report.kind.label}")
                report.android?.let { a -> log("Phát hiện: $a") }
                if (report.arch.isNotEmpty()) log("Kiến trúc: ${report.arch.joinToString()}")
                report.blockReason?.let { r -> log("Không thể ghi: $r") }
            }.onFailure {
                source = null
                _state.update { s -> s.copy(analyzing = false, report = null, status = "Lỗi đọc image") }
                log("Không phân tích được image: ${it.message}")
            }
        }
    }

    fun setVerify(v: Boolean) = _state.update { it.copy(verify = v) }

    fun start() {
        val s = _state.value
        val drive = s.selectedDrive ?: return
        val src = source ?: return
        if (!s.canStart) return
        _state.update { it.copy(busy = true, progress = null, status = "Đang ghi...") }
        // Chạy hẳn trên IO và không suspend sau runCatching, để khi huỷ vẫn cập nhật được trạng thái.
        job = viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val result = runCatching {
                ScsiBlockDevice.open(ctx.getSystemService(UsbManager::class.java), drive.device).use { dev ->
                    Flasher(dev, onProgress = { p -> _state.update { it.copy(progress = p) } }, log = ::log)
                        .flash(src, s.verify)
                }
            }
            result.onSuccess {
                _state.update { st -> st.copy(busy = false, status = "HOÀN TẤT ✔") }
                log("Hoàn tất. Có thể rút USB ra an toàn.")
            }.onFailure { e ->
                val cancelled = e is CancellationException
                _state.update { st -> st.copy(busy = false, status = if (cancelled) "Đã huỷ" else "THẤT BẠI") }
                log(if (cancelled) "Đã huỷ — dữ liệu trên USB không còn nguyên vẹn." else "Lỗi: ${e.message}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
