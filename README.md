# DLS26 Tactical AutoBot (Android APK — No Root, Landscape)

Ứng dụng Android tự động chơi **Dream League Soccer 2026 (DLS 26)** ở chế độ màn hình ngang (**Landscape**) không cần Root:
- **Nhận diện Minimap & Sa bàn (OpenCV 4.9.0 Maven Central)**: Nhận diện toàn bộ chấm **Áo đỏ** (cầu thủ + Thủ môn áo đỏ) làm **Team của mình**, tự động xác định vị trí **Thủ môn áo đỏ** và tấn công về phía ngược lại.
- **Giả lập cảm ứng đa điểm mượt mà (`AccessibilityService.dispatchGesture`)**: Duy trì kéo Joystick liên tục bằng chuỗi `continueStroke(..., willContinue = true)` qua callback `onCompleted()` kết hợp **Multi-Touch** bấm đồng thời các nút chuẩn DLS 26:
  - **Nút A**: Sút bóng (Tấn công) / Xoạc bóng (Phòng ngự)
  - **Nút B**: Chuyền thấp (Tấn công) / Áp sát (Phòng ngự)
  - **Nút C**: Chuyền bổng, tạt cánh (Tấn công) / Đổi người (Phòng ngự)
- **HUD nổi tích hợp nút Cài đặt (`⚙️`) ngay trong game**: Chỉnh chiến thuật, đảo hướng tấn công, kéo slider hoặc kéo thả chấm neo ROI/nút A-B-C trực tiếp đè lên game không cần thoát ra ngoài.

## 1. Cách Push lên GitHub & Tải file APK tự động

1. Giải nén dự án và push lên nhánh `main` (hoặc `master`) của repository GitHub của bạn:
   ```bash
   git init
   git add .
   git commit -m "Initial commit: DLS26 Tactical AutoBot"
   git branch -M main
   git remote add origin https://github.com/<username>/<repo>.git
   git push -u origin main
   ```
2. Vào tab **Actions** trên GitHub -> chọn workflow **Build DLS26 Tactical AutoBot APK** -> tải artifact **`DLS26-Tactical-AutoBot-debug-apk`** để lấy file `app-debug.apk`.
3. *(Tùy chọn)* Gắn tag `v1.0.0` và push để tự động tạo **GitHub Release** đính kèm file `.apk`.

## 2. Hướng dẫn cài đặt & cấp quyền trên Android 13 / 14 / 15

1. Cài đặt `app-debug.apk` từ bộ nhớ máy.
2. **Mở khóa Restricted Settings (nếu công tắc Accessibility bị khóa xám)**:
   - Mở app **DLS26 Tactical AutoBot** -> bấm nút **Mở Thông tin ứng dụng (Bật Restricted Settings)**.
   - Chạm biểu tượng **3 dấu chấm (`⋮`)** góc trên phải -> chọn **Cho phép cài đặt bị hạn chế (Allow restricted settings)** -> xác thực PIN/vân tay.
3. Quay lại app cấp đủ 4 quyền: **Overlay**, **Accessibility**, **Thông báo**, và bấm **KHỞI ĐỘNG BOT** để cấp quyền **MediaProjection**.

## 3. Sử dụng trong game DLS 26

- Vào trận đấu DLS 26 (màn hình ngang), bấm **⚙️ Cài đặt** trên thanh HUD nổi -> chọn **🎯 Kéo thả chấm neo ROI & Nút A / B / C** để căn khớp khung `MINIMAP ROI`, tâm `JOY` và 3 nút `A`, `B`, `C`, sau đó bấm **Lưu ROI**.
- Bấm **▶ RUN** trên thanh HUD để bot tự động thi đấu; bật **Debug Overlay** để quan sát chấm đỏ phe ta, vòng vàng Thủ môn đỏ (`GK`) và mũi tên hướng tấn công.
