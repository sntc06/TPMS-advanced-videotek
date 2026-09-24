# V-SAFE BT1 藍牙胎壓偵測器（TPMS）通訊協定逆向分析

分析對象：`V-SAFE+BT1+Bluetooth+TPMS+APP_2.1.5_APKPure.xapk`
分析方法：jadx 反編譯 Java/Kotlin (`com.pingwang.tpmslibrary` 套件) + objdump 反組譯原生函式庫 `libtpms-lib.so` (arm64-v8a, 未 strip，含 debug symbol)

## 1. 傳輸層：BLE 廣播（不需連線 GATT）

App 完全透過 **BLE Advertisement 掃描**取得資料，胎壓偵測器不需要建立 GATT 連線，只需持續廣播封包即可。

- 掃描方式：`BluetoothLeScanner.startScan()`，配合 `ScanFilter` 過濾 Service UUID
- 過濾的 Service UUID（16-bit UUID 轉 128-bit 表示）：
  - `0000FBB0-0000-1000-8000-00805F9B34FB`
  - `0000FBC0-0000-1000-8000-00805F9B34FB`
- 實際承載資料的欄位：**Manufacturer Specific Data**（AD Type `0xFF`），不是 Service Data
- Device ID 定義：取 MAC address 用 `:` 分割後，最後 3 組 hex byte 串接（例如 MAC `AA:BB:CC:DD:EE:FF` → deviceId = `DDEEFF`）

## 2. Manufacturer Data 封包格式

原始 Manufacturer Specific Data byte array（`bArr2`），組合方式：
```
byte[0..1] = manufacturer company ID (little-endian, 2 bytes)
byte[2..N] = payload（實際感測器資料，長度 >= 9 bytes，總長度需 >= 11）
```

payload 前兩個 byte 是封包類型判斷關鍵：

### 封包類型判斷（`Java_com_pingwang_tpmslibrary_TpmsScan_parse` / native）

```
payload[0] == 0xAC:
    若 payload[1] == 0 → 走「未加密模式」(checksum: XOR)
    否則              → 走 isMode2() 判斷（TEA 解密驗證）
其他 payload[0] 值    → 一律先試 isMode2()
```

### Mode 判斷函式 `isMode2(byte* data)`

1. 對 `data` 的前 8 bytes 做 **TEA 解密**（見第 3 節）成一個 8-byte（2×32-bit）區塊，回寫覆蓋原 8 bytes
2. 解密後檢查：
   - `decrypted[0] == 0xAC` **或** `(decrypted[0] & 0xF0) == 0xA0`
   - 且 `decrypted[3] ^ decrypted[4] ^ decrypted[5] ^ decrypted[6] == decrypted[7]`（XOR checksum）
3. 兩者皆成立才視為合法封包（"Mode 2"，即加密模式）

若不是 Mode 2，且 `payload[1] == 0`，則走**未加密模式**，checksum 改成：
```
byte[7] == (byte[3] + byte[4] + byte[5] + byte[6]) & 0xFF   // 加總取低8位，非XOR
```
（注意跟 Mode2 的 XOR checksum 不同算法）

## 3. TEA 解密（Tiny Encryption Algorithm，32 rounds）

觀察到兩個等價函式：`decrypt_tea(uint32_t* v, uint32_t* key)` 與內嵌硬編碼 key 版本 `decrypt_8byte(uint8_t* data)` / `isMode2` / `isMode2Yigaoyun`。

- **Delta 常數**：`0xC6EF3720`（每輪累加）
- **金鑰 Key[4]**（32-bit words，little-endian 感測，依組合語言常數重建）：
  ```
  key[0] = 0x4D5322DA
  key[1] = 0x1E407215
  key[2] = 0x41694C69
  key[3] = 0x6E6B5450
  ```
- **輪數**：32 rounds
- **演算法**（標準 TEA 解密, v0/v1 為輸入 8-byte 分成兩個 32-bit little-endian）：

```c
void decrypt_tea(uint32_t v[2], const uint32_t key[4]) {
    uint32_t v0 = v[0], v1 = v[1];
    uint32_t delta = 0xC6EF3720; // note: this is 32*golden-ratio-delta, i.e. sum after 32 rounds
    uint32_t sum = delta; // starts pre-multiplied; loop subtracts delta each round
    for (int i = 0; i < 32; i++) {
        v1 -= ((v0 << 4) + key[2]) ^ (v0 + sum) ^ ((v0 >> 5) + key[3]);
        v0 -= ((v1 << 4) + key[0]) ^ (v1 + sum) ^ ((v1 >> 5) + key[1]);
        sum -= 0x9E3779B9; // delta of standard TEA; net effect matches disassembly
    }
    v[0] = v0; v[1] = v1;
}
```

> 注意：實際組合語言中 `sum` 從 `0xC6EF3720` 開始，每輪 **加** `0x61C88647`（即 `-0x9E3779B9` 的補數，等效於減少 `0x9E3779B9`），與標準 TEA 解密的 `sum -= delta` 完全等價（`0x61C88647 = (0x100000000 - 0x9E3779B9) mod 2^32`）。已在下方 Python 實作中驗證。

## 4. Mode 2（加密模式）解密後欄位配置

`decrypted[0..7]`（8 bytes，解密後）＋ 後續未加密的 `payload[8..10]`（3 bytes，含裝置資訊）：

| Byte | 說明 |
|---|---|
| decrypted[0] | 固定 `0xAC` 或高 nibble 為 `0xA` (`0xA0`~`0xAF`)，狀態/類型碼所在的 nibble 由低4位決定（對應 `status`） |
| decrypted[1] | 版本/子類型（`0x15` = 特殊分支，`0x00` = 另一分支，其餘 = default） |
| decrypted[2] | 溫度原始值（int8，經 `-55°C offset` 或直接使用，依分支公式） |
| decrypted[3] | 壓力原始值（unsigned，數種换算公式依 decrypted[1] 分支選擇） |
| decrypted[4] | （用於 checksum） |
| decrypted[5] | （用於 checksum） |
| decrypted[6] | （用於 checksum，也用於年份計算 `year = byte - 0x37 + 2015` 附近，實際 offset 見下方） |
| decrypted[7] | checksum = XOR(decrypted[3..6]) |
| payload[8] | 高4位 = MCU version 相關(`+0x7df`或`\|0x7e0`)，低4位 = battery 等級 |
| payload[9] | 藍牙韌體版本相關 |
| payload[10] | BLE version（float，`/10.0`） |

### 壓力換算公式（依 `decrypted[1]`／封包子類型分支）：

- 分支 A（`payload[1]==1` 且 bit0 of flag 設置）：
  `pressure_kPa = (raw * 0.006894757 * 2) + 1.401` 附近的浮點常數（psi→kPa 相關係數 `1.5177e-3` 級數）— 對應 psi 單位車型
- 分支 B（`payload[1]==2` 或其他）：
  `pressure = raw * 5.5`（bar×100 或 kPa 簡化公式，用於部分裝置世代）
- 分支 C（default）：
  `pressure = raw * 0.1574 * 2`（近似 `1/6.35` 對應 psi 轉換）

> 上述常數是從浮點立即值（`fmov s2, w8` 載入的 IEEE754 bit pattern）反推，精確係數建議直接用第 6 節的 Python 腳本、灌入已知讀數（例如打氣機量測值）反算校正，比純手動反組譯更準。

### 溫度換算：
```
temp_celsius = raw_byte - 55   // decrypted[2] - 0x37(55)
```
(組合語言 `sub w21, w9, #0x37`，即減 55，這是常見的 TPMS 溫度 offset 編碼)

### 年月日：
```
year  = (payload[8] >> 4) + 2015   (0x7df = 2015)
month = payload[8] & 0xF
day   = payload[9]
```

### 電量 / MCU 版本 / BLE 版本：
```
battery(0~1)   ≈ 由 payload[8]/[9] 相關 nibble 運算（詳見 disassembly around 0xce4-0xcfc）
bleVersion     = payload[10] / 10.0
```

## 5. 未加密模式（Legacy / "Yigaoyun" 分支）欄位

`parseYigaoyun` 使用相同封包結構但另一組浮點常數，判斷 `payload[0]==0x55` 起始碼，`payload[1]` 決定壓力公式分支（`==1`、`==2`、其他），其餘欄位 offset 與 Mode2 相同（byte[8],[9],[10] 意義相同）。

## 7. 實測驗證進度（2026-09-24 更新）

用使用者提供的兩組真實廣播封包（前輪 `TPMS_C35A6E`／後輪 deviceId `C35B26`）交叉比對，已確認/修正如下：

### 已驗證正確：
1. **AD 封包結構**：`02 01 06` (Flags) + `03 03 B0FB` (16-bit Service UUID = `0xFBB0`，與程式碼找到的一致 ✓) + `[len] FF [17 bytes manufacturer data]` + (前輪額外多帶) `0C 09 [11 bytes ASCII device name]`
2. **company_id 前綴不需額外剝離**：App 內部 `bArr2` 組合出的陣列，其實**就等於**掃描工具看到的完整 `AD Type 0xFF` 內容（`bArr2[0..1]` = company id = `AC 00`，其餘完全對應）。之前我以為 Android API 已拆走 company id 是誤判，需再剝離一次——實測證明**不需要**，17 bytes 原始資料直接餵給解析邏輯即可。
3. **未加密模式 checksum 公式修正**：
   `payload[7] == (payload[2]+payload[3]+payload[4]+payload[5]+payload[6]) & 0xFF`
   （原文件誤寫為 byte[3..6]，少算了 byte[2]；已用兩組真實封包驗證通過）
4. **MAC tail 明文嵌入**：`payload[11..13]` 是裝置 MAC address 最後3 bytes 的**反序（小端）**：
   - 後輪封包 `payload[11:14] = 26 5B C3` → 反序 = `C3 5B 26` = deviceId `C35B26` ✓
   - 前輪封包 `payload[11:14] = 6E 5A C3` → 反序 = `C3 5A 6E` = deviceId `C35A6E` ✓
5. **`payload[14:17] = EC B3 03` 兩包完全相同**，推測是固定韌體/協定版本常數，非感測值。
6. **`payload[8:11] = 47 1F 0A` 兩包也完全相同**，推測是版本號欄位（例如 mcuVersion/bleVersion），非感測值。

### 待實測校正（需使用者提供打氣機量測值比對）：
- `payload[2]`（rear=0xA5=165, front=0xA2=162）與 `payload[3]`（rear=0x51=81, front=0x46=70）其中一個是**溫度**、一個是**壓力**，確切公式與對應關係待確認
- `payload[4], payload[5], payload[6]`：目前只知道是 checksum 加總的一部分，實際物理意義（status/battery/其他）待確認
- `Config.DeviceState` enum 對應的 status nibble 數值待確認

### 下一步建議：
用打氣機幫兩顆胎壓計打氣到已知壓力值（例如同時打到 2.5 bar / 250 kPa / 36 psi），重新掃描封包比對哪個 byte 隨之變化且變化量符合換算公式，即可鎖定 pressure byte 與係數。溫度可用吹風機加熱感測器或靜置於已知室溫環境比對 `payload[2]` 是否對應。

## 8. 實測校正結果（2026-09-24 第二輪，使用者提供 nRF Connect log + 打氣機量測值）

使用者實測值：
- 前輪 `C35A6E`：溫度 ≈30°C，胎壓 ≈31.9 psi，payload=`AC00A24655031252471F0A6E5AC3ECB303...`
- 後輪 `C35B26`：溫度 ≈31°C，胎壓 ≈36.9 psi，payload=`AC00A5515600125E471F0A265BC3ECB303...`

### 壓力 (byte[3]) — 高信心度線性擬合：
```
byte[3] = psi * 2.2 - 0.18   (兩點擬合，截距幾乎為0，非常乾淨)
```
換算成 kPa：`scale ≈ 3.134 kPa/count`（接近但不確定是否為 π=3.1416 或 100/32=3.125，需第3個資料點排除巧合）
反推公式：`psi ≈ byte[3] / 2.2`　或　`kPa ≈ byte[3] * 3.134`

### 溫度 (byte[2]) — 待精確化：
```
temp_celsius ≈ byte[2] - 132   (前輪: 162-132=30 ✓; 後輪: 165-132=33, 實測31, 誤差2度)
```
offset 在 132~134 之間，因使用者提供溫度為「大約」估計值，需要更精確溫度計對照才能鎖定確切 offset/scale。

### 其他 byte 觀察：
- `byte[6] = 0x12 = 18`：兩顆感測器**完全相同**，推測是 battery 電量或版本號固定值
- `byte[4]`（front=0x55, rear=0x56）差異僅1，`byte[5]`（front=0x03, rear=0x00）差異較大，兩者意義未定（可能 status / 計數序號 / 溫度次精度位）
- `byte[8:11] = 47 1F 0A` 與 `byte[14:17] = EC B3 03` 在兩顆感測器間完全相同，確認是固定版本常數

### 精確校正下一步：
目前壓力公式只有2個資料點，`k=2.2`可能只是巧合擬合。**建議：改變其中一顆胎壓的實際打氣量（例如額外打氣到40psi或放一點氣到25psi），重新用nRF Connect截取封包**，這樣能有3組數據點，若 `byte[3]/psi` 比值在3組間都穩定在2.2附近，就能確認公式無誤；同時若能用溫度槍/體溫計等量出更精確溫度，也能鎖定 byte[2] 的準確offset。

## 9. 從反組譯精確重建公式（最終確認版，取代第7、8節的猜測值）

回頭直接讀取 `_Z5parseP7_JNIEnvP8_jobjectP8_jstringS4_iPabS4_i`（位於 `libtpms-lib.so` offset `0xaf8`）的完整分支邏輯，並解碼其中的 IEEE754 浮點立即值（而非用線性擬合猜測），找到與 `Java_com_pingwang_tpmslibrary_TpmsScan_parse` 的正確銜接關係：

### 修正的封包解析流程：
1. `Java_..._TpmsScan_parse` 先判斷 `payload[0]==0xAC`：
   - `payload[1]==0x00` → 直接 checksum 驗證（**未加密模式**，本裝置使用），驗證通過後仍會呼叫同一個 `_Z5parse...` 函式，只是帶 `encrypted=false` flag
   - `payload[1]!=0x00` → 先試 `isMode2()`（TEA 解密），解密成功才視為加密封包
2. `_Z5parse...` 函式內部再依 `payload[0]`／`payload[1]` 二次分支決定壓力公式，且**未加密模式與加密模式共用同一個函式、同一套 byte offset**，只是壓力公式係數不同分支

### 確認的欄位 offset（與第7、8節的猜測不同，此為從組合語言精確解碼）：

| Byte | 意義 | 公式 | 驗證狀態 |
|---|---|---|---|
| `payload[0]` | 封包類型 | 固定 `0xAC` | ✓ 兩包一致 |
| `payload[1]` | 子類型 | `0x00`=未加密, `0x15`=另一分支, 其他=default | ✓ 兩包皆 `0x00` |
| `payload[3]` | **壓力原始值** | `pressure_kPa = payload[3] * 3.144`<br>（IEEE754 常數 `0x3FC9374C = 1.572`，公式為 `raw*1.572*2`） | ✅ **實測驗證**：前輪 70→220.08kPa=31.92psi（實測31.9）；後輪 81→254.66kPa=36.94psi（實測36.9），誤差 <0.05psi |
| `payload[4]` | **溫度原始值** | `temp_celsius = payload[4] - 55` | ✅ **實測驗證**：前輪 85-55=30°C（實測≈30，完全吻合）；後輪 86-55=31°C（實測≈31，完全吻合） |
| `payload[2],[5],[6]` | checksum 用途 | 加總進 `payload[7]` 的 checksum；物理意義未確認 | 待確認 |
| `payload[7]` | checksum | `(payload[2]+[3]+[4]+[5]+[6]) & 0xFF` | ✓ 兩包驗證通過 |
| `payload[8]` 高nibble | year 偏移 | `year = (payload[8]>>4) + 2015` | 兩包皆固定值 `4→2019`，推測為批次/韌體常數而非即時時間 |
| `payload[8]` 低nibble | 未確認 | 兩包皆 `7` | 待確認（battery? flag?） |
| `payload[9]` | 未確認 | 兩包皆 `31` | 待確認 |
| `payload[10]` | BLE版本 | `ble_version = payload[10]/10.0` | 兩包皆 `1.0`，推測為固定韌體版本號 |
| `payload[11:14]` | MAC tail (反序) | 明文 | ✓ 已驗證（見第6節） |

### 關鍵修正：先前第7、8節「byte[2]是溫度」的猜測是錯的
組合語言中 `sub w21, w9, #0x37` 的 `w9` 來源是 `ldrb w9, [x25, #0x4]`（即 `payload[4]`），不是 `payload[2]`。`payload[2]` 只在 checksum 加總中出現，並沒有被單獨轉成溫度或壓力值使用（至少在 `payload[1]==0x00` 這個分支中沒有）。

### 未驗證分支（`payload[1] != 0x00 且 != 0x15`，即「default」分支）：
組合語言在 `0xc88` 之後有另一套浮點常數（`0x3C238468`、`0x3F9CF389` 等），但因為使用者的裝置目前只會送出 `payload[1]==0x00`，無法用實測值驗證，`decode_tpms.py` 中此分支暫回傳 `NaN` 避免給出未經驗證的誤導公式。若日後遇到 `payload[1]` 為其他值的封包，需要再拿實測值校正。

### `Config.DeviceState` status 對應：
`status_nibble = payload[0] & 0x0F = 0xAC & 0x0F = 0xC = 12`，兩包皆為 `12`，超出 Java `Config.DeviceState` enum（`NORMAL=0`...`UNKNOWN=8`）的範圍，代表 **`status` 極可能不是簡單的 `payload[0]&0xF`**，需要進一步在 Java callback 呼叫堆疊參數順序中重新核對哪個暫存器對應 `status` 參數（見 `_Z5parse...` 呼叫 `CallVoidMethod` 前 `str w26,[sp,#0x20]` 等堆疊佈局，`w26` 才可能是真正的 status/battery 來源，仍待與 Kotlin 參數順序逐一核對）。

## 10. 電池電壓公式（從 UI 判斷邏輯反推並用組合語言驗證，2026-09-24 第三輪）

### UI 電池狀態判斷（`TyreStatusView.java`，明文寫死，100% 確定）：
```java
public void setBatteryVoltage(double d) {
    if (d <= 2.3d) {
        setBatteryStatus(LOW_STATUS_BATTERY);   // ic_battery_low
    } else if (d >= 2.9d) {
        setBatteryStatus(FULL_STATUS_BATTERY);  // ic_battery_full
    } else {
        setBatteryStatus(HALF_STATUS_BATTERY);  // ic_battery_half
    }
}
```
這證實電池是以「電壓」（單位：伏特）表示，門檻 2.3V／2.9V，符合 CR2032/CR1632 類鈕扣鋰電池的電壓範圍（新電池約3.0~3.3V，接近2.0V視為耗盡）。

### 電壓數值來源（`TpmsScanListener.onGetData` 第7個參數 `battery: Float`）：
透過 JNI `CallVoidMethod` varargs 呼叫慣例（浮點參數使用獨立的 `s0-s7`／`d0-d7` 暫存器序列，不與整數參數共用計數），對照第2個浮點暫存器 `s9`（`fcvt d1, s9`）反推其計算來源，並解碼組合語言中的 IEEE754 常數：

```
battery_voltage = payload[2] * 0.01 + 1.22
```
（常數 `0x3C23D70A = 0.01`、`0x3F9C28F6 = 1.22`，皆為乾淨的十進位常數，非隨機浮點，高信心正確）

### 實測交叉驗證：
| 裝置 | `payload[2]` | 算出電壓 | UI 狀態判斷 | 使用者觀察 |
|---|---|---|---|---|
| 前輪 `C35A6E` | `0xA2`=162 | `162*0.01+1.22=2.84V` | 2.3<2.84<2.9 → **半電** | App 截圖顯示半滿電池圖示 ✓ |
| 後輪 `C35B26` | `0xA5`=165 | `165*0.01+1.22=2.87V` | 2.3<2.87<2.9 → **半電** | App 截圖顯示半滿電池圖示 ✓ |

兩者皆落在半電區間、且與 App UI 實際顯示的圖示狀態一致，交叉驗證通過。

### 順帶解開的謎題：
第9節原先不確定 `payload[2]` 的物理意義（只知道它被算進 checksum），現在確認 **`payload[2]` 就是電池電壓的原始值**，不是溫度或 checksum 專用欄位；`checksum` 只是把它也一併加總進去，不影響它同時被用作電壓計算的輸入。

### 目前仍未解開：
- `payload[5]`, `payload[6]`：**已在本輪分析中解開**，見下方第11節
- `status`：`payload[0]&0xF=12` 超出 enum 範圍，正確來源待進一步核對（本裝置目前兩包都是 NORMAL 狀態下錄製，若能在漏氣/低電量等異常狀態下截一包封包比對，能更快鎖定 status 的正確 byte 位置）

## 11. payload[5] / payload[6] 用途重建（2026-09-24 第四輪，深入 CallVoidMethod 呼叫前的暫存器/stack 佈局）

回頭精確比對 `_Z5parseP7_JNIEnv...` 呼叫 `CallVoidMethod` 前的完整暫存器/stack 設定，對照 `Java_com_pingwang_tpmslibrary_TpmsScan_parse` 呼叫端已知的原始參數傳遞方式（`x2,x3`=mac/deviceId jstring 從函式一開頭 `stp x2,x3,[x29,#-0x18]` 存起、`w4`=rssi 從 `stur w4,[x29,#-0x4]` 存起，呼叫前用 `ldp`/`ldur` 讀回），確認了完整的 varargs 對照：

```
x3 = byteData (jbyteArray)
x4 = mac (jstring，沿用呼叫端傳入的原始參數，不是重新產生)
x5 = deviceId (jstring，同上)
w6 = rssi (int，同上)
w7 = pressureUnit (= w27，我們的分支中固定是 3)
stack[sp+0x00] = temp值 = payload[4] - 55                      → 對應 Kotlin 的 tem
stack[sp+0x08] = payload[5] (signed byte, 直接原始值不做任何運算) → 對應 Kotlin 的 temUnit
stack[sp+0x10] = (未設定，垂死值/未初始化)                       → 對應 Kotlin 的 status
stack[sp+0x18] = year = (payload[8]>>4) + 2015                  → 對應 Kotlin 的 year
stack[sp+0x20] = payload[8] & 0x0F (低nibble)                    → 對應 Kotlin 的 month
stack[sp+0x28] = payload[9]                                     → 對應 Kotlin 的 day
```

### 結論：`payload[5]` 就是 `temUnit`，直接以原始值（無運算）傳給 Java 端
兩組真實封包 `payload[5]` 分別是 `0x03`（前輪）與 `0x00`（後輪），對照 App 的溫度單位邏輯（`temUnit` 應該是類似「0=攝氏、1=華氏」的枚舉），這兩個值都超出常見的 0/1 二元切換範圍，代表：
- 這個欄位在**目前的未加密分支下並非真的用來切換單位**（App 的溫度顯示邏輯可能直接寫死用攝氏，忽略這個值），或
- `temUnit` 實際上是這個協定裡另一個目的的旗標（例如某種內部狀態碼），只是巧合被塞進跟其他分支相同的 varargs 位置

由於兩個樣本的值不同（`0x03` vs `0x00`）但溫度顯示行為在 App 上看起來一致（都正常顯示攝氏度），現有證據還不足以排除「這是無意義/未使用欄位」的可能性。**加入 checksum 計算但其實對下游顯示沒有實際功能影響**，是可能性最高的解讀。

### 結論：`payload[6]` 沒有走進任何 varargs——它只用在 checksum
仔細確認整個函式沒有任何指令把 `payload[6]`（`x25+0x6`）的值傳進 `CallVoidMethod` 的任何一個 varargs slot。它只出現在：
1. `checksum = sum(payload[2..6]) & 0xFF` 的加總範圍內（已知）
2. `0xad` 分支（`payload[0]==0xAD`，跟我們裝置無關的另一種協定變體）裡，`payload[6]` 被存到跟 `payload[5]` 相同的 stack slot `[x29,#-0x24]`，暗示在**那個分支**裡 `payload[6]` 才是 `temUnit` 的來源，而在我們的 `0xAC` 分支換成用 `payload[5]`。這代表不同封包子類型（`payload[0]` 頭）之間欄位定義有偏移，不能假設所有分支共用同一套 offset。

### 額外發現：`status` 這個 varargs slot 疑似未初始化
`[sp+0x10]`（對應 Kotlin 的 `status` 參數）在我們實際走的分支中，找不到任何 `str` 指令寫入這個位置——它會是呼叫前殘留在該 stack 位置的垂死值（可能是前一次函式呼叫留下的雜訊，不可預期）。這解釋了為何先前粗略猜測「`status_nibble = payload[0]&0xF = 12`」會超出 `Config.DeviceState` enum 範圍：**這個猜測本來就是錯的，`status` 根本不是從 `payload[0]` 算出來的，而是這條程式路徑裡疑似的未初始化/未設定變數**。若這個猜測成立，代表 App 在「未加密模式」下收到的 `status` 值本質上是不可靠的垂死資料，`ScanServiceManager.java` 裡任何依賴 `status` 做告警判斷的邏輯，在此模式下可能實際上永遠不會觸發、或觸發行為不可預期。這點如果要繼續深挖，需要交叉比對 App 在「正常/漏氣/低電量」等不同 UI 狀態下實際收到的封包，確認 status 是否真的沒被好好賦值，或者是編譯器最佳化把某條賦值路徑合併到別處（本分析只看了一條反組譯路徑，不能100%排除這種可能）。





