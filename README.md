# BearApp Installer

แอป Android สำหรับดาวน์โหลดและติดตั้ง BearApp โดยดึงข้อมูลจาก GitHub Releases อัตโนมัติ

## ฟีเจอร์

- ตรวจสอบเวอร์ชันล่าสุดจาก GitHub Releases API
- แสดง changelog และรายละเอียดการอัปเดต
- ดาวน์โหลด APK พร้อม progress bar
- ติดตั้งแอปหลังดาวน์โหลดเสร็จ
- รองรับ Dark Mode
- Design ธีมหมีน้ำตาล (Material Design 3)

## วิธีปล่อย APK ใหม่

1. สร้าง tag บน GitHub:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
2. GitHub Actions จะ build APK อัตโนมัติ
3. APK จะถูกอัปโหลดใน Releases

## Build

```bash
./gradlew assembleRelease
```

APK จะอยู่ที่: `app/build/outputs/apk/release/`

## Requirements

- Android 7.0+ (API 24)
- Internet connection
