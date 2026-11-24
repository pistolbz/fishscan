# ScanPDF

App Android nội bộ: chọn ảnh tài liệu trong máy, tự nhận 4 góc, căn theo khổ giấy, làm sáng rồi xuất PDF.

- Xử lý ảnh và tạo PDF **hoàn toàn offline**: tài liệu không bao giờ rời khỏi máy.
- **Không cần Google Play Services.**
- Không xin quyền đọc/ghi bộ nhớ: ảnh được chọn qua trình chọn ảnh của hệ thống, PDF được lưu qua hộp thoại "Lưu thành".
- Chỉ dùng mạng để kiểm tra và tải bản cập nhật từ GitHub Releases.

## Tính năng (giai đoạn 1)

| Bước | Cách làm |
|---|---|
| Nhập ảnh | Chọn nhiều ảnh cùng lúc. Ảnh được chép vào bộ nhớ riêng của app, xoay đúng chiều theo EXIF. |
| Nhận 4 góc | OpenCV: Canny / Otsu → contour → `approxPolyDP`. Sau đó tinh chỉnh bằng giao điểm các cạnh (`fitLine`), nên thẻ bo góc vẫn ra đúng góc vuông. Tờ giấy phải sáng hơn vùng ngay bên ngoài, để không cắt nhầm bảng hay khối chữ bên trong trang. |
| Giấy tràn khung | Ảnh không thấy đủ 4 góc thì app đo góc nghiêng của dòng chữ để **xoay thẳng**, và **cắt dải nền tối** (bàn, bàn phím) ở mép ảnh. App hiện cảnh báo và gợi ý lần sau chụp chừa ít nền quanh tờ giấy. |
| Chỉnh góc tay | Kéo 4 chấm tròn. Khi kéo có kính lúp phóng to 2,5 lần. |
| Căn khổ | Tự do, A4, A5, Letter, CCCD/thẻ (85,6×54 mm), danh thiếp (90×54 mm). Xuất ở 300 DPI (A4 là 2480×3508 px), ảnh gốc được đọc ở độ phân giải đầy đủ. |
| Làm sáng | Gốc / Làm sáng / Xám / Trắng đen. "Làm sáng" gồm: làm phẳng nền (xóa bóng), levels (chữ đen tuyệt đối, nền trắng), gamma, làm nét. Có thanh chỉnh độ sáng và tương phản, xoay 90°. |
| Xuất PDF | Giấy: trang đúng khổ. Thẻ: in **đúng kích thước thật** trên trang A4. Lưu file hoặc chia sẻ (Zalo, email…). |

Chỉnh sửa **không phá hủy**: ảnh gốc giữ nguyên, mỗi trang chỉ lưu tham số (`PageEdit`), nên mở lại sửa được bất kỳ lúc nào.

Giới hạn hiện tại: danh sách trang chỉ tồn tại trong phiên làm việc. Đóng app là mất, chưa có thư viện tài liệu.

## Cập nhật app (từ 0.2.0)

**Khi nào app kiểm tra:** app hỏi GitHub Releases của repo khai báo trong `updateRepo` ([app/build.gradle.kts](app/build.gradle.kts)) vào hai lúc:
- Khi mở app, tối đa 6 giờ một lần.
- Khi bấm menu ⋮ → **Kiểm tra cập nhật**.

Bản debug không tự kiểm tra.

**Chọn bản nào:** bản có số phiên bản cao nhất và có file `.apk` đính kèm, kể cả bản đánh dấu *pre-release*.

**Cách cập nhật:**
1. Tải APK về, có thanh tiến trình.
2. Kiểm tra SHA-256 theo `digest` mà GitHub cung cấp.
3. Kiểm tra APK đúng là ScanPDF, phiên bản mới hơn và **cùng khóa ký**.
4. Android hiện hộp thoại xác nhận cài đặt.

Lần cập nhật đầu tiên, Android sẽ yêu cầu bật **"Cho phép từ nguồn này"** cho ScanPDF. Bật xong quay lại app thì quá trình cài tự tiếp tục.

Repo phải để **công khai** thì app mới đọc được danh sách Releases mà không cần token.

## Phát hành bản mới

1. Sửa `appVersionName` trong [app/build.gradle.kts](app/build.gradle.kts), ví dụ `"0.2.1"`. `versionCode` tự tăng theo.
2. Build và chạy test: `./gradlew :app:testDebugUnitTest :app:assembleRelease`
   - Bản release chỉ được ký khi máy có `keystore.properties`.
   - **Luôn dùng cùng một khóa ký**, nếu không máy người dùng sẽ không cập nhật đè được.
3. Commit, gắn tag `vX.Y.Z` rồi `git push origin main vX.Y.Z`.
4. Trên GitHub, tạo Release từ tag đó và đính kèm file APK. Tên file tùy ý, miễn có đuôi `.apk`.

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

`RealSamplesDiagnosticTest` chạy bộ nhận góc và bộ lọc trên **ảnh chụp thật** để chẩn đoán. Test này tự bỏ qua nếu không đặt biến môi trường:

```bash
SCANPDF_SAMPLES=/duong/dan/thu-muc-anh ./gradlew :app:testDebugUnitTest --tests '*RealSamplesDiagnosticTest'
```

Kết quả được in ra cho từng ảnh (tìm được 4 góc / chỉ xoay thẳng / không làm gì). Ảnh có khung đánh dấu và ảnh kết quả được lưu ở `app/build/real-samples/`.

⚠️ Ảnh thật có thể chứa tài liệu nội bộ, nên luôn **để chúng ngoài repo** và không commit thư mục `app/build/real-samples/`.

Lưu ý: unit test Android biên dịch dựa trên `android.jar`, nên **không dùng được `java.awt` hay `ImageIO`**. Muốn tạo ảnh mẫu thì dùng OpenCV (`Imgcodecs`).

## Tài liệu liên quan

Tài liệu phân tích và kế hoạch các giai đoạn tiếp theo (`docs/phan-tich-camscanner-va-ke-hoach.md`) chỉ lưu trên máy phát triển, không đưa lên repo.
