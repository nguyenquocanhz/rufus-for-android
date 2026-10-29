# Rufus for Android

App Android tạo USB boot từ image **Android x86** (Android-x86, Bliss OS, PrimeOS…) và **Android ARM**
(Raspberry Pi, Rockchip, Allwinner…) ngay trên điện thoại, qua cáp **USB OTG**, **không cần root**.

Dựa trên cách làm của [Rufus](https://github.com/pbatard/rufus) (chế độ DD — `WriteDrive()` trong `src/format.c`),
viết lại bằng Kotlin cho Android.

## Tính năng
- Ghi thô (DD) lên USB qua giao thức USB Mass Storage Bulk-Only + SCSI (`usb/ScsiBlockDevice.kt`).
- Giải nén khi đang ghi: `.xz`, `.gz`, `.zip` (chưa hỗ trợ `.zst`, `.bz2`).
- Nhận diện image (`image/ImageInspector.kt`):
  - ISO Android x86 (ISOHybrid): phát hiện `kernel` + `system.sfs/efs/img` + `ramdisk.img`, kiến trúc từ `EFI/BOOT/boot*.efi` và header kernel.
  - Disk image ARM: bảng phân vùng MBR/GPT, phân vùng kiểu Android (`super`, `userdata`…), bo mạch Raspberry Pi / Rockchip / Allwinner.
  - Chặn các file không boot được từ USB: Android sparse image, `boot.img`, gói OTA (`payload.bin`), ISO không hybrid.
- Xoá 1 MiB cuối ổ (GPT dự phòng cũ) trước khi ghi, ghi lại tối đa 4 lần khi lỗi.
- Đọc lại kiểm tra (CRC32 từng khối 1 MiB) + SHA-256 dữ liệu đã ghi.

## Build
Yêu cầu JDK 17+ và Android SDK (platform 36).

```bash
cd android
./gradlew assembleRelease      # APK: app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # test bộ nhận diện image
```

Bản release đang ký bằng debug key để cài thử được ngay — thay bằng keystore riêng trước khi phát hành.

## Giới hạn hiện tại
- Chỉ chế độ DD. Chưa có chế độ ISO (format FAT32 + chép file) và chưa có persistence `/data` cho Android-x86.
- Ghi chạy trong app (giữ màn hình sáng); thoát app khi đang ghi sẽ huỷ thao tác.
- Cần máy hỗ trợ USB Host/OTG; USB tiêu thụ nhiều điện có thể cần hub có nguồn.
