# DLS26 Tactical AutoBot (Android APK — No Root)

Ứng dụng Android tự động chơi **Dream League Soccer 2026 (DLS 26)** dựa trên thị giác máy tính (**OpenCV 4.9.0** từ Maven Central), quét **Minimap & Sa bàn** thời gian thực qua `MediaProjection` + `ImageReader` (15–20 FPS) và giả lập cảm ứng đa điểm mượt mà bằng `AccessibilityService.dispatchGesture`.

## 1. Cách Fork / Push lên GitHub & Tải file APK tự động

1. **Tạo repository mới trên GitHub** (hoặc Fork repo này), giải nén toàn bộ mã nguồn và push lên nhánh `main` (hoặc `master`):
   ```bash
   git init
   git add .
   git commit -m "Initial commit: DLS26 Tactical AutoBot"
   git branch -M main
   git remote add origin https://github.com/<username>/<repo>.git
   git push -u origin main
   ```
2. Mở tab **Actions** trên GitHub -> chọn workflow **Build DLS26 Tactical AutoBot APK** đang chạy.
3. Sau khi build hoàn tất (~2–3 phút), cuộn xuống mục **Artifacts** và tải về **`DLS26-Tactical-AutoBot-debug-apk`** -> giải nén để lấy file `app-debug.apk`.
4. *(Tùy chọn)* Để tự động tạo **GitHub Release** kèm file APK, gắn tag phiên bản và push:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

## 2. Hướng dẫn cài đặt & cấp quyền trên Android 13 / 14 / 15

Vì file APK được cài trực tiếp ngoài Google Play Store, Android 13+ sẽ khóa quyền **Accessibility** theo cơ chế **Restricted Settings (Cài đặt bị hạn chế)**. Thực hiện theo 3 bước sau:

1. **Cài đặt APK**: Mở `app-debug.apk` trên điện thoại và cho phép cài từ nguồn không xác định.
2. **Mở khóa Restricted Settings (Android 13/14/15)**:
   - Vào **Cài đặt (Settings) -> Ứng dụng (Apps) -> Xem tất cả ứng dụng -> DLS26 Tactical AutoBot**.
   - Nhấn vào biểu tượng **3 dấu chấm (`⋮`)** ở góc trên bên phải -> chọn **Cho phép cài đặt bị hạn chế (Allow restricted settings)** -> xác thực vân tay / mã PIN.
3. **Cấp đủ 4 quyền trong ứng dụng**:
   - **Overlay (`SYSTEM_ALERT_WINDOW`)**: Hiển thị thanh điều khiển nổi & Debug Overlay.
   - **Accessibility (`DLS26 Tactical AutoBot Touch`)**: Vào *Trợ năng -> Ứng dụng đã tải xuống* và bật công tắc.
   - **Thông báo (`POST_NOTIFICATIONS`)**: Duy trì Foreground Service ổn định.
   - **Quay màn hình (`MediaProjection`)**: Chấp nhận hộp thoại hệ thống khi bấm **KHỞI ĐỘNG BOT**.

## 3. Mẹo căn chỉnh ROI & Chạy hiệu quả

- Mở DLS 26 ở chế độ ngang (Landscape), sau đó bấm **Hiệu chuẩn tọa độ (ROI Calibration)** để kéo chính xác khung `MINIMAP ROI` bao trọn bản đồ radar và đặt các điểm `JOY`, `A`, `B`, `C`, `NEXT` trùng khớp với nút trên màn hình máy bạn (tương thích 16:9, 19.5:9, 20:9).
- Bật **Debug Overlay** trong 1–2 phút đầu để kiểm tra chấm cầu thủ phe ta (Xanh), đối thủ (Đỏ), bóng (Vàng) và mũi tên hướng di chuyển AI trước khi tắt HUD để tiết kiệm pin.
