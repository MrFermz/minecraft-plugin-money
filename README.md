# minecraft-plugin-money

Economy/currency plugin สำหรับ ecosystem นี้ — เป็น "สกุลเงิน" กลางให้ระบบหลักที่จะเพิ่มมาทีหลังเรียกใช้ บน Paper 26.2

ส่วนหนึ่งของ multi-module ecosystem ที่อธิบายไว้ใน [CLAUDE.md ของ root repo](../CLAUDE.md) — depend on `minecraft-plugin-core` แบบ `compileOnly` + `depend: [Core]` แล้ว register `EconomyService` เข้า Bukkit `ServicesManager` (ชื่อ plugin ใน `/pl` = `Money`)

## Commands

ทุกอย่างอยู่ใต้คำสั่งเดียว **`/money`** (alias: `/eco`, `/economy`, `/bal`, `/balance`) — พิมพ์ `/money` เปล่า ๆ = เช็คเงินตัวเอง

| Subcommand | Permission | คำอธิบาย |
|------------|-----------|----------|
| `/money` | `money.balance` | เช็คเงินของตัวเอง |
| `/money help` | — | แสดงรายการคำสั่ง |
| `/money balance [player]` | `money.balance` / `money.balance.others` | ดูยอดตัวเอง หรือของคนอื่น |
| `/money pay <player> <amount>` | `money.pay` | โอนเงินให้ผู้เล่นที่ online |
| `/money top` | `money.baltop` | จัดอันดับคนรวยสุด (top 10) |
| `/money give\|take\|set <player> <amount>` | `money.admin` (+ child `money.admin.give/take/set`) | คำสั่งแอดมิน |
| `/money reset <player>` | `money.admin.reset` | รีเซ็ตเป็นยอดเริ่มต้น |
| `/money log [player] [limit]` | `money.admin.log` | ดู transaction log ล่าสุด (default 10, สูงสุด 50) |

`/money` รองรับ tab-complete ของ subcommand / ชื่อผู้เล่น / จำนวน (subcommand แอดมินโชว์เฉพาะคนที่มี perm)

### Permissions (default)

| Node | Default | |
|------|---------|--|
| `money.balance` | ทุกคน | ดูยอดตัวเอง |
| `money.pay` | ทุกคน | โอนเงิน |
| `money.baltop` | ทุกคน | ดู leaderboard |
| `money.balance.others` | op | ดูยอดคนอื่น |
| `money.admin` | op | parent ครอบ `money.admin.give/take/set/reset/log` |

## Config (`plugins/antitle/money.yml`)

> config ของ plugin เป็นไฟล์แบนในโฟลเดอร์รวมของ ecosystem ที่ `plugins/antitle/money.yml` (ไม่ใช่ `plugins/Money/`) — resolve ผ่าน `EcosystemData` ของ core ดู [CLAUDE.md → Config directory บน server](../CLAUDE.md#config-directory-บน-server)


```yaml
currency:
  name-singular: "Coin"
  name-plural: "Coins"
  symbol: "$"          # prefix เวลา format เช่น $1,250.00
  decimals: 2          # ปัดทศนิยมกี่ตำแหน่ง
  starting-balance: 100.0
transaction-log:
  enabled: true        # บันทึกทุกธุรกรรมลงตาราง money_transactions (false = ปิด)
```

> ไม่มีการตั้งค่า DB ที่นี่ — engine เลือกที่ core ที่เดียว (`plugins/antitle/config.yml` → `database.type`) money เก็บใน central DB ของ core เสมอ (ตาราง `money_*`)

## API ให้ plugin อื่นเรียก

ระบบหลักเรียกผ่าน core ไม่แตะ class ภายในของ money:

```java
EconomyService eco = CoreApi.economy(getServer()).orElseThrow();
if (eco.has(uuid, price)) {
    eco.withdraw(uuid, price);
}
```

จำนวนเงินเป็น `BigDecimal` ทั้งหมด (exact math, ไม่ใช้ `double`) ดู `EconomyService` / `EconomyResponse` ใน core

## Storage

- เก็บใน **central database ของ ecosystem** ที่ core เป็นเจ้าของ ผ่าน `SqlMoneyStorage` ตาราง **`money_balances(id, uuid, balance)`** — `id` เป็น PK gen ด้วย UUID (convention ทุกตาราง), `uuid` (ผู้เล่น) เป็น UNIQUE ใช้ทำ upsert, เก็บ balance เป็น string เพื่อความแม่นยำของ `BigDecimal`
- ดึง `DataSource` จาก `CoreApi.database(server)` — **ไม่สร้าง connection pool เอง** (ดู [Database](../CLAUDE.md#database)); ถ้า core ไม่มี `DatabaseService` ตอน enable, money จะ disable ตัวเองพร้อม log error
- **รองรับทุก engine ของ core** (sqlite/postgresql/mysql/mariadb) — `SqlMoneyStorage` ใช้ `DatabaseService.dialect()` เลือก DDL/UPSERT ให้ถูกของแต่ละ engine (เปลี่ยน engine ที่ core ฝั่ง money ไม่ต้องแก้)
- **ผู้เล่นใหม่ = insert ลง DB ทันทีตอน join** (`AccountListener` → `seedIfAbsent` → `storage.create()` แบบ async) ไม่รอ flush
- การเปลี่ยนยอดเงินเขียนแบบ buffered: `put()` mark dirty ในเมมโมรี, `flush()` upsert เป็น batch transaction (ทุก 1 นาทีบน async thread + ตอน disable) — JDBC ไม่อยู่บน main thread

> เลือก/ตั้งค่า engine ที่ global config ของ core (`plugins/antitle/config.yml` → `database.*`) ไม่ใช่ที่ money — money ไม่มี setting เรื่อง DB engine เลย

## Transaction log (audit trail)

บันทึก **ทุกการเปลี่ยนยอดเงิน** ลงตาราง **`money_transactions`** ใน central DB เพื่อให้ตรวจสอบได้ว่าใครโอน/ทำธุรกรรมอะไร — เปิด/ปิดด้วย `transaction-log.enabled` (default `true`; ปิดแล้วใช้ `TransactionLog.NOOP`)

- **บันทึกที่ชั้น `MoneyEconomyService`** ไม่ใช่ที่ command — `deposit/withdraw/setBalance/transfer` ทุกตัวที่สำเร็จจะ record 1 แถว ดังนั้น **plugin อื่นที่เรียกผ่าน `EconomyService` ก็ถูกบันทึกด้วย** (ไม่หลุด); `transfer` บันทึกแถวเดียวต่อการโอน (ไม่แตกเป็น debit+credit)
- แต่ละแถวมี `id` (PK gen ด้วย UUID) + เก็บ: `ts`, `type` (`PAY/TRANSFER/GIVE/TAKE/SET/RESET/DEPOSIT/WITHDRAW`), `actor` (คนสั่ง — null = console/system), `from_uuid`/`to_uuid`, `amount`, `from_balance`/`to_balance`, `reason` — player ref **เก็บเป็น UUID ล้วน** (resolve ชื่อตอนแสดงผล ไม่เก็บชื่อค้างให้เพี้ยน)
- เขียนแบบ buffered เหมือน balances: `record()` ลง queue + async flush (debounced) + periodic flush ทุก 1 นาที + flush ตอน disable — append-only ไม่มี upsert; JDBC ไม่อยู่บน main thread
- attribution: command layer ส่ง `TxMeta` (type + actor) ให้ service ส่วน `EconomyService` interface ปกติ (ที่ plugin อื่นเรียก) จะ log เป็น system (actor = null, type `DEPOSIT/WITHDRAW/TRANSFER`)
- ดูในเกม: `/money log [player] [limit]` (perm `money.admin.log`) — query แบบ async แล้ว resolve ชื่อกลับมาแสดง; ข้อมูลดิบอยู่ใน DB ให้ webconfig อ่านต่อได้

## Build

```
./gradlew :minecraft-plugin-money:build
# ได้ minecraft-plugin-money/build/libs/minecraft-plugin-money-<version>.jar (shadow jar, ไม่ bundle core)
```
