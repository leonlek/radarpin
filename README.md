# RadarPin

แอปแผนที่ Android เตือน **กล้องจับความเร็ว / จุดสนใจ / ปั๊ม EV** สำหรับจอรถ BYD (DiLink) และมือถือ — บันทึกจุดที่อยากให้เตือน แล้วแอปจะเตือนเมื่อขับเข้าใกล้

## ดาวน์โหลด / ติดตั้ง
👉 **https://leonlek.github.io/radarpin/** — หรือดูที่ [Releases](../../releases)

sideload ไฟล์ `.apk` (เปิด "ติดตั้งจากแหล่งที่ไม่รู้จัก" ก่อน) · ต้องมีเน็ตครั้งแรกเพื่อโหลดแผนที่

## ฟีเจอร์
- 🗺️ แผนที่ **MapLibre + OpenFreeMap** (ไม่พึ่ง Google/GMS)
- 📍 บันทึก / แก้ไข / ลบจุด · toggle เตือนต่อจุด · รัศมีเตือนปรับได้ · 3 ประเภท (กล้อง/จุดสนใจ/ปั๊ม EV)
- 🔴 เตือน: วงกลมแดง + แบนเนอร์ + เสียงบี๊บ + เสียงพูด TTS (ปิดไว้เป็นค่าเริ่มต้น) + overlay ทับแอปอื่น
- 🚗 ปุ่มกลับตำแหน่ง · ความเร็ว · รองรับจอหมุน · foreground service (ทำงานต่อตอนสลับแอป)

## เทคโนโลยี
Kotlin · Jetpack Compose · MapLibre 13 · Room (SQLite) · Foreground Service · minSdk 24 / targetSdk 36

## Build
```bash
./gradlew assembleDebug
```
ผลลัพธ์: `app/build/outputs/apk/debug/app-debug.apk` (arm64-v8a / armeabi-v7a, ~32MB)

## หมายเหตุ
เดโม / ใช้ส่วนตัว — ตอนขึ้นสโตร์ควรเปลี่ยน `applicationId` (ปัจจุบัน `com.bydmapcam`), เลี่ยงคำว่า "BYD" ในชื่อ (เครื่องหมายการค้า) และตรวจลิขสิทธิ์ map tile
