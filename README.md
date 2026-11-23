# ScanPDF

App Android nội bộ: chọn ảnh tài liệu trong máy, tự nhận 4 góc, căn theo khổ giấy, làm sáng rồi xuất PDF.

- Chạy **hoàn toàn offline**.
- **Không cần Google Play Services.**
- **Không xin quyền nào**: ảnh được chọn qua trình chọn ảnh của hệ thống, PDF được lưu qua hộp thoại "Lưu thành".

## Tính năng (giai đoạn 1)

| Bước | Cách làm |
|---|---|
| Nhập ảnh | Chọn nhiều ảnh cùng lúc. Ảnh được chép vào bộ nhớ riêng của app, xoay đúng chiều theo EXIF. |
| Nhận 4 góc | OpenCV: Canny / Otsu → contour → `approxPolyDP`. Sau đó tinh chỉnh bằng giao điểm các cạnh (`fitLine`), nên thẻ bo góc vẫn ra đúng góc vuông. Không nhận được thì dùng cả ảnh và hiện cảnh báo. |
| Chỉnh góc tay | Kéo 4 chấm tròn. Khi kéo có kính lúp phóng to 2,5 lần. |
| Căn khổ | Tự do, A4, A5, Letter, CCCD/thẻ (85,6×54 mm), danh thiếp (90×54 mm). Giấy xuất ở 200 DPI, thẻ ở 300 DPI. |
| Làm sáng | Gốc / Làm sáng (xóa bóng, nền trắng) / Xám / Trắng đen. Có thanh chỉnh độ sáng và tương phản, xoay 90°. |
| Xuất PDF | Giấy: trang đúng khổ. Thẻ: in **đúng kích thước thật** trên trang A4. Lưu file hoặc chia sẻ (Zalo, email…). |

Chỉnh sửa **không phá hủy**: ảnh gốc giữ nguyên, mỗi trang chỉ lưu tham số (`PageEdit`), nên mở lại sửa được bất kỳ lúc nào.

Giới hạn hiện tại: danh sách trang chỉ tồn tại trong phiên làm việc. Đóng app là mất, chưa có thư viện tài liệu.

## Build và chạy

**Yêu cầu:** Android Studio (JDK 17+), Android SDK 35. Máy chạy Android 8.0 (API 26) trở lên.

1. Mở thư mục `scanpdf/` bằng Android Studio.
2. Cắm điện thoại (bật USB debugging) rồi bấm **Run**.

Hoặc dùng dòng lệnh:

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # unit test (chạy trên máy tính, không cần điện thoại)
```

Bản **debug** có thêm thư viện x86_64 để chạy trên máy ảo, nên nặng khoảng 52 MB. Bản **release** chỉ có ARM, khoảng 23 MB. Để phát hành nội bộ cần:
- Tạo khóa ký của công ty (`signingConfig`).
- Đổi `applicationId` trong [app/build.gradle.kts](app/build.gradle.kts) sang tên miền của công ty.

## Cấu trúc mã

```
app/src/main/java/com/scanpdf/app/
├── model/        Quad, PaperPreset, FilterType, PageEdit, ScanPage
├── imaging/      DocumentDetector (nhận góc), PerspectiveCorrector (làm phẳng),
│                 Enhancer (bộ lọc), OutputSizing, ImageLoader, PageRenderer (Bitmap ⇄ OpenCV)
├── pdf/          JpegPdfWriter (ghi PDF, nhúng JPEG), PageLayout (khổ trang), PdfExporter
└── ui/           ScanViewModel, HomeScreen, EditScreen, QuadEditor (kéo góc + kính lúp)
```

`imaging/` (trừ `ImageLoader` và `PageRenderer`) và `pdf/` (trừ `PdfExporter`) **không phụ thuộc Android**, nên được test trực tiếp trên máy tính.

## Test

Unit test chạy **mã xử lý ảnh thật** bằng OpenCV bản desktop (`org.openpnp:opencv`), trên các ảnh giả lập:

- **Nhận góc:**
  - A4 chụp nghiêng có bóng đổ.
  - Giấy trắng trên nền sáng (độ tương phản thấp).
  - Thẻ bo góc xoay 12°.
  - Ảnh không có tài liệu.
- **Bộ lọc:** xóa bóng, chữ vẫn đậm.
- **Kích thước đầu ra:** tỉ lệ A4 và thẻ.
- **PDF:** kiểm tra cấu trúc và bảng xref, ảnh JPEG nhúng nguyên byte, số thập phân đúng định dạng kể cả khi máy đặt ngôn ngữ tiếng Việt.

`PipelineDemoTest` chạy toàn bộ chuỗi xử lý và lưu ảnh từng bước cùng `demo.pdf` vào `app/build/pipeline-demo/` để xem bằng mắt.

Lưu ý: unit test Android biên dịch dựa trên `android.jar`, nên **không dùng được `java.awt` hay `ImageIO`**. Muốn tạo ảnh mẫu thì dùng OpenCV (`Imgcodecs`).

## Tài liệu liên quan

Tài liệu phân tích và kế hoạch các giai đoạn tiếp theo (`docs/phan-tich-camscanner-va-ke-hoach.md`) chỉ lưu trên máy phát triển, không đưa lên repo.
