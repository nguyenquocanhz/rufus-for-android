package io.github.nguyenquocanhz.rufusandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nguyenquocanhz.rufusandroid.image.ImageInspector.fmtSize

@Composable
fun MainScreen(state: UiState, vm: MainViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::selectImage)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Rufus for Android", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Tạo USB boot Android x86 / ARM qua cáp OTG",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------------- Thiết bị
            Section("Thiết bị", action = {
                IconButton(onClick = vm::refreshDrives, enabled = !state.busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Làm mới")
                }
            }) {
                if (state.drives.isEmpty()) {
                    Text("Chưa có USB. Cắm USB qua OTG rồi bấm làm mới.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.drives.forEach { d ->
                    val selected = state.selectedDrive?.device?.deviceName == d.device.deviceName
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected, enabled = !state.busy) { vm.selectDrive(d) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, enabled = !state.busy)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(d.label, fontWeight = FontWeight.Medium)
                            Text(
                                if (selected) d.detail ?: "Đang đọc..." else d.detail ?: "Chạm để chọn",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---------------- Image
            Section("Image") {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !state.busy && !state.analyzing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.imageName == null) "CHỌN FILE (.iso .img .xz .gz .zip)" else "CHỌN FILE KHÁC") }

                if (state.analyzing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Đang phân tích...")
                    }
                }
                state.imageName?.let { Kv("Tên file", "$it (${fmtSize(state.imageSize)})") }
                state.report?.let { r ->
                    Kv("Loại", r.kind.label)
                    r.android?.let { Kv("Android", it) }
                    r.volumeLabel?.takeIf { it.isNotBlank() }?.let { Kv("Nhãn ISO", it) }
                    if (r.arch.isNotEmpty()) Kv("Kiến trúc", r.arch.joinToString(", "))
                    r.board?.let { Kv("Bo mạch", it) }
                    Kv("Nén", r.compression.label + (r.innerName?.let { " → $it" } ?: ""))
                    if (r.partitions.isNotEmpty()) Kv("Phân vùng", r.partitions.joinToString("\n"))
                    r.notes.forEach { Note(it, MaterialTheme.colorScheme.secondaryContainer) }
                    r.warnings.forEach { Note("⚠ $it", MaterialTheme.colorScheme.tertiaryContainer) }
                    r.blockReason?.let { Note("⛔ $it", MaterialTheme.colorScheme.errorContainer) }
                }
            }

            // ---------------- Tuỳ chọn
            Section("Tuỳ chọn") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.verify, onCheckedChange = vm::setVerify, enabled = !state.busy)
                    Text("Đọc lại để kiểm tra sau khi ghi (khuyên dùng)")
                }
                Kv("Chế độ ghi", "DD (ghi thô từng sector, giống chế độ DD của Rufus)")
            }

            // ---------------- Trạng thái
            Section("Trạng thái") {
                val p = state.progress
                if (state.busy && p != null) {
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    val speed = if (p.bytesPerSec > 0) " • ${fmtSize(p.bytesPerSec.toLong())}/s" else ""
                    Text("${p.phase.label}: ${"%.1f".format(p.fraction * 100)}% (${fmtSize(p.done)} / ${fmtSize(p.total)})$speed")
                } else if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(state.status, fontWeight = FontWeight.SemiBold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { confirm = true },
                    enabled = state.canStart,
                    modifier = Modifier.weight(1f),
                ) { Text("BẮT ĐẦU") }
                OutlinedButton(
                    onClick = vm::cancel,
                    enabled = state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("HUỶ") }
            }

            // ---------------- Log
            Section("Nhật ký") {
                SelectionContainer {
                    Text(
                        state.log.takeLast(60).joinToString("\n").ifEmpty { "—" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.heightIn(min = 60.dp),
                    )
                }
            }
        }
    }

    if (confirm) {
        val drive = state.selectedDrive
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Xoá toàn bộ dữ liệu?") },
            text = {
                Text(
                    "TOÀN BỘ dữ liệu trên \"${drive?.label}\" (${drive?.capacity?.let(::fmtSize)}) sẽ bị xoá vĩnh viễn " +
                        "và thay bằng nội dung của ${state.imageName}.\n\nTiếp tục?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirm = false; vm.start() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("XOÁ VÀ GHI") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Huỷ") } },
        )
    }
}

@Composable
private fun Section(title: String, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun Kv(key: String, value: String) {
    Row {
        Text(key, Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Note(text: String, bg: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}
