# Bear Store

แอป Android สำหรับดาวน์โหลดและจัดการแอปในระบบ Bear — ดึงข้อมูลจาก `apps_config.json` บน GitHub แบบ real-time

## ฟีเจอร์

- **In-App Download Manager** — ดาวน์โหลด APK ผ่าน OkHttp โดยตรง แสดง progress ใน card แต่ละแอปแบบ real-time
- **Auto-Install** — เปิด installer อัตโนมัติทันทีที่ดาวน์โหลดเสร็จ ไม่ต้องกดปุ่มแยก
- **ตรวจสอบสถานะติดตั้ง** — เทียบเวอร์ชันกับแอปที่ติดตั้งบนเครื่อง แสดง 3 สถานะ:
  - ยังไม่ติดตั้ง → ปุ่ม ดาวน์โหลด
  - ติดตั้งแล้ว เวอร์ชันล่าสุด → ปุ่ม ถอนการติดตั้ง
  - มีอัปเดต → ปุ่ม อัปเดต + ถอนการติดตั้ง
- **ถอนการติดตั้ง** — กดถอนการติดตั้งได้โดยตรงจาก Bear Store
- **รองรับ Android 11+** — ใช้ `QUERY_ALL_PACKAGES` ตรวจสอบ package ทุกตัวได้
- **Material Design 3** — ธีมหมีน้ำตาล, รองรับ Dark Mode

## แอปที่รองรับ

| แอป | Package | เวอร์ชันล่าสุด |
|-----|---------|--------------|
| YouTube | `app.bear.tube` | ดูใน [apps_config.json](apps_config.json) |
| YouTube Music | `app.bear.music` | ดูใน [apps_config.json](apps_config.json) |
| Bear MicroG | `app.bear.android.gms` | ดูใน [apps_config.json](apps_config.json) |

เพิ่ม/แก้ไขแอปได้ที่ [apps_config.json](apps_config.json) บน branch `main`

## Requirements

- Android 10+ (API 29)
- การเชื่อมต่ออินเทอร์เน็ต
- อนุญาต "ติดตั้งแอปจากแหล่งที่ไม่รู้จัก" สำหรับ Bear Store

## Tech Stack

- **Kotlin** + AndroidX
- **AndroidViewModel** + LiveData + Coroutines
- **OkHttp** สำหรับดาวน์โหลดและดึงข้อมูล
- **Gson** สำหรับ parse JSON
- **Material Design 3** + RecyclerView + ViewBinding
- `minSdk 29` · `targetSdk 36`

## CI/CD — GitHub Actions

ทุก push ขึ้น `main` จะ:

1. build Release APK พร้อม sign ด้วย keystore ที่เก็บใน GitHub Secrets
2. สร้าง tag อัตโนมัติ `v1.0.<run_number>`
3. สร้าง GitHub Release พร้อม APK และ changelog

ดาวน์โหลด APK ล่าสุดได้ที่ [Releases](../../releases/latest)

## Permissions

| Permission | เหตุผล |
|-----------|--------|
| `INTERNET` | ดึงข้อมูลแอปและดาวน์โหลด APK |
| `REQUEST_INSTALL_PACKAGES` | ติดตั้ง APK ที่ดาวน์โหลดมา |
| `REQUEST_DELETE_PACKAGES` | แสดง dialog ถอนการติดตั้ง (API 28+) |
| `QUERY_ALL_PACKAGES` | ตรวจสอบเวอร์ชันแอปที่ติดตั้ง (Android 11+) |

## การตั้งค่า Keystore (สำหรับ maintainer)

เพิ่ม GitHub Secrets ต่อไปนี้ใน repository settings:

```
KEYSTORE_BASE64   — keystore ที่ encode เป็น base64
KEYSTORE_PASSWORD — password ของ keystore
KEY_ALIAS         — alias ของ key
KEY_PASSWORD      — password ของ key
```

สร้าง keystore ใหม่:
```bash
keytool -genkey -v -keystore bearstore-release.jks \
  -alias bearstore -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 bearstore-release.jks
```
