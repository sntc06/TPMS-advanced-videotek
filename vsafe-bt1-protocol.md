# V-SAFE BT1 藍牙胎壓偵測器（TPMS）通訊協定

分析對象：`V-SAFE+BT1+Bluetooth+TPMS+APP_2.1.5_APKPure.xapk`

分析方法：jadx 反編譯 Java/Kotlin（`com.pingwang.tpmslibrary` 套件）＋ objdump 反組譯原生函式庫
`libtpms-lib.so`（arm64-v8a，未 strip，含 debug symbol），並用兩顆實體感測器的封包與實測值交叉驗證。

每一項都標示驗證等級：

| 標記 | 意義 |
|---|---|
| **實測** | 用實體感測器的封包與獨立量測值（打氣機、App UI 顯示）驗證過 |
| **反組譯** | 從組合語言精確解碼，但本裝置不會走到該路徑，無法實測 |
| **推測** | 有跡象但證據不足，已註明理由 |

---

## 1. 傳輸層

純 **BLE Advertisement 廣播**，感測器不建立 GATT 連線。

- 掃描 API：`BluetoothLeScanner.startScan()`，以 `ScanFilter` 過濾 Service UUID
- 過濾的 Service UUID（16-bit 轉 128-bit 表示）：
  - `0000FBB0-0000-1000-8000-00805F9B34FB`
  - `0000FBC0-0000-1000-8000-00805F9B34FB`
- 資料載於 **Manufacturer Specific Data**（AD Type `0xFF`），不是 Service Data
- 原廠 App 的 deviceId：取 BLE MAC 用 `:` 分割後串接最後 3 組（`AA:BB:CC:DD:EE:FF` → `DDEEFF`）

## 2. 廣播封包結構（實測）

```
02 01 06                          Flags
03 03 B0FB                        16-bit Service UUID = 0xFBB0
[len] FF [17 bytes]               Manufacturer Specific Data
0C 09 [11 bytes ASCII]            Complete Local Name，例如 TPMS_C35A6E（並非每包都帶）
```

那 17 bytes 的組成：

```
byte[0..1]  = company id（little-endian）
byte[2..16] = payload
```

本裝置的 company id 固定是 `AC 00`。實作上不需要額外剝離 company id——原廠 App 內部組出的陣列就等於掃描工具看到的完整
`AD Type 0xFF` 內容，17 bytes 直接餵給解析邏輯即可。

以下所有 `payload[n]` 的索引都是從 company id 之後起算（即 17 bytes 陣列的 `byte[n+2]`）。

## 3. 封包類型判斷

```
payload[0] == 0xAC:
    payload[1] == 0x00  → 未加密模式（本裝置走這條）
    payload[1] != 0x00  → 先試 isMode2()，TEA 解密成功才視為加密封包
payload[0] 其他值       → 一律先試 isMode2()
```

兩種模式**共用同一個解析函式與同一套 byte offset**（`_Z5parseP7_JNIEnv...`，`libtpms-lib.so` offset
`0xaf8`），只有壓力公式的係數依分支不同。

不同的 `payload[0]` 之間欄位定義會偏移，不能假設所有分支共用同一套 offset。已知的例子：`0xAD` 分支把
`payload[6]` 存進 `0xAC` 分支用來存 `payload[5]` 的同一個 stack slot（反組譯）。

## 4. Payload 欄位（未加密模式，`payload[0]=0xAC`／`payload[1]=0x00`）

| Byte | 意義 | 公式 | 驗證等級 |
|---|---|---|---|
| `[0]` | 封包類型 | 固定 `0xAC` | 實測 |
| `[1]` | 子類型 | `0x00` = 未加密 | 實測 |
| `[2]` | 電池電壓原始值 | `volt = raw * 0.01 + 1.22` | 實測 |
| `[3]` | 胎壓原始值 | `kPa = raw * 3.144` | 實測 |
| `[4]` | 溫度原始值 | `°C = raw - 55` | 實測 |
| `[5]` | 以 `temUnit` 參數傳給 Java 端，原始值不做運算 | — | 反組譯（用途見第 6 節） |
| `[6]` | 只進 checksum，沒有傳給 Java 端任何參數 | — | 反組譯 |
| `[7]` | checksum | `(payload[2]+[3]+[4]+[5]+[6]) & 0xFF` | 實測 |
| `[8]` 高 nibble | 年份偏移 | `year = (raw >> 4) + 2015` | 反組譯 |
| `[8]` 低 nibble | 以 `month` 參數傳出 | `payload[8] & 0x0F` | 反組譯 |
| `[9]` | 以 `day` 參數傳出 | 原始值 | 反組譯 |
| `[10]` | BLE 版本 | `raw / 10.0` | 反組譯 |
| `[11..13]` | MAC 末 3 bytes 的**反序**，明文 | — | 實測 |
| `[14..16]` | 固定常數 `EC B3 03` | — | 實測（兩顆感測器相同） |

`payload[8..10]`、`payload[14..16]` 在兩顆感測器之間完全相同，所以「年月日」欄位實際上是韌體或批次常數，
不是即時時間。

### 換算公式的來源

三個公式都來自組合語言裡的 IEEE754 浮點立即值，不是曲線擬合：

```
電壓  0x3C23D70A = 0.01      0x3F9C28F6 = 1.22
胎壓  0x3FC9374C = 1.572     公式為 raw * 1.572 * 2 = raw * 3.144
溫度  sub w21, w9, #0x37     w9 來自 ldrb w9, [x25, #0x4]，即 payload[4]
```

胎壓一刻度約 0.456 psi，溫度一刻度 1°C，電壓一刻度 0.01V。

### 電池電壓的 UI 門檻

`TyreStatusView.java` 明文寫死，可用來反向驗證電壓公式：

```java
if (d <= 2.3d)      setBatteryStatus(LOW_STATUS_BATTERY);
else if (d >= 2.9d) setBatteryStatus(FULL_STATUS_BATTERY);
else                setBatteryStatus(HALF_STATUS_BATTERY);
```

門檻 2.3V／2.9V 符合 CR2032／CR1632 鈕扣鋰電池的電壓範圍。

## 5. 實測樣本

兩顆感測器，實測值來自打氣機與 App UI 顯示：

```
前輪 C35A6E   AC 00 A2 46 55 03 12 52 47 1F 0A 6E 5A C3 EC B3 03
後輪 C35B26   AC 00 A5 51 56 00 12 5E 47 1F 0A 26 5B C3 EC B3 03
```

| | 前輪 | 後輪 |
|---|---|---|
| `payload[2]` | `0xA2`=162 → **2.84 V** | `0xA5`=165 → **2.87 V** |
| `payload[3]` | `0x46`=70 → 220.08 kPa = **31.92 psi**（實測 31.9） | `0x51`=81 → 254.66 kPa = **36.94 psi**（實測 36.9） |
| `payload[4]` | `0x55`=85 → **30°C**（實測 30） | `0x56`=86 → **31°C**（實測 31） |
| `payload[7]` | `0x52`=82，加總相符 | `0x5E`=94，加總相符 |
| `payload[11..13]` | `6E 5A C3` → 反序 `C35A6E` | `26 5B C3` → 反序 `C35B26` |

胎壓誤差 <0.05 psi，溫度完全吻合。兩顆電壓都落在 2.3V 到 2.9V 之間，與 App 顯示的半滿電池圖示一致。

## 6. 未確認的欄位

**`payload[5]`（以 `temUnit` 傳出）**：兩顆感測器分別是 `0x03` 與 `0x00`，都超出「0=攝氏／1=華氏」這種二元
切換的範圍，而兩者在 App 上的溫度顯示行為一致（都是攝氏）。推測這個欄位在未加密分支下並未實際用於切換單位。

**`payload[6]`**：反組譯確認它沒有進入任何傳給 Java 的參數，只出現在 checksum 加總中。

**`status` 參數**：對應的 stack slot（`[sp+0x10]`）在本裝置走的分支中找不到任何 `str` 指令寫入，是呼叫前殘留
的未初始化值。若成立，代表未加密模式下 App 收到的 `status` 不可靠，`ScanServiceManager.java` 中依賴 `status`
的告警邏輯在此模式下行為不可預期。本分析只追了一條反組譯路徑，不能完全排除賦值被編譯器最佳化到別處。要確認需
在漏氣或低電量等異常狀態下截包比對。

**`payload[1] != 0x00` 且 `!= 0x15` 的 default 分支**：組合語言在 offset `0xc88` 之後有另一套浮點常數
（`0x3C238468`、`0x3F9CF389`），但本裝置只送 `payload[1]==0x00`，無法實測校正。

## 7. 加密模式（Mode 2）

本裝置不使用，以下全部來自反組譯，未經實測驗證。

### `isMode2(byte* data)` 的判斷

1. 對前 8 bytes 做 TEA 解密，回寫覆蓋原資料
2. 檢查 `decrypted[0] == 0xAC` 或 `(decrypted[0] & 0xF0) == 0xA0`
3. 且 `decrypted[3] ^ decrypted[4] ^ decrypted[5] ^ decrypted[6] == decrypted[7]`

兩者皆成立才視為合法加密封包。注意加密模式的 checksum 是 **XOR**，未加密模式是**加總取低 8 位**，算法不同。

### TEA 參數（32 rounds）

```
delta  = 0xC6EF3720
key[0] = 0x4D5322DA
key[1] = 0x1E407215
key[2] = 0x41694C69
key[3] = 0x6E6B5450
```

```c
void decrypt_tea(uint32_t v[2], const uint32_t key[4]) {
    uint32_t v0 = v[0], v1 = v[1];
    uint32_t sum = 0xC6EF3720;
    for (int i = 0; i < 32; i++) {
        v1 -= ((v0 << 4) + key[2]) ^ (v0 + sum) ^ ((v0 >> 5) + key[3]);
        v0 -= ((v1 << 4) + key[0]) ^ (v1 + sum) ^ ((v1 >> 5) + key[1]);
        sum -= 0x9E3779B9;
    }
    v[0] = v0; v[1] = v1;
}
```

組合語言中 `sum` 每輪是**加** `0x61C88647`，與標準 TEA 的 `sum -= 0x9E3779B9` 等價
（`0x61C88647 = 2^32 - 0x9E3779B9`）。

### 另一個變體

`parseYigaoyun` 使用相同封包結構但另一組浮點常數，起始碼判斷 `payload[0]==0x55`，`payload[1]` 決定壓力公式
分支，`payload[8..10]` 意義與上述相同。

## 8. 原廠 App 的掃描過濾層

起因：用 nRF Connect 從另一支手機 clone 複製的廣播封包，TPMS-advanced 收得到，原廠 App 完全沒反應。以下是
`TpmsScan.onScanResult`（`com/pingwang/tpmslibrary/TpmsScan.java`）依序的四道關卡。

### 一、ScanFilter 過濾 Service UUID（硬體層）

```java
ScanFilter.Builder().setServiceUuid(adUuid1).build()   // FBB0
ScanFilter.Builder().setServiceUuid(adUuid2).build()   // FBC0
ScanSettings.Builder().setScanMode(2).build()          // SCAN_MODE_LOW_LATENCY
```

`ScanFilter` 在支援的晶片上會下放到藍牙控制器執行（`BluetoothAdapter.isOffloadedFilteringSupported()`），
不符合的封包不會喚醒應用處理器。原廠**只用 service UUID 過濾，沒有用 `setDeviceAddress()`**。

### 二、MAC 白名單（軟體層）— clone 封包被丟棄的原因

```java
String deviceId = tpmsScan.getDeviceId(device.getAddress());
if (strArr != null && !(strArr.length == 0) && !ArraysKt.contains(strArr, deviceId)) {
    return;
}
```

**原廠 App 的感測器身分來源是廣播端的真實 MAC 位址，不是封包內容。** 用手機 clone 時 `manufacturerData` 可以
完整複製，但廣播端 MAC 是那支手機的（Android 預設還會用隨機的 resolvable private address），算出的 `deviceId`
不在白名單內，封包在進入 native 解析前就被丟棄。

這也說明 `payload[11..13]`（MAC 末 3 bytes）對原廠 App 是冗餘資訊——它從不讀這三個 byte。推測是留給不方便取得
MAC 的平台使用。

**對 TPMS-advanced 的意義**：`RawVSafe.id()` 從 `payload[11..13]` 取 ID，不依賴 MAC，所以 clone 封包收得到。
這讓「用另一支手機模擬感測器」成為可用的測試手段，可重現低電量、高胎壓等不易在真車上製造的情境。反之若要讓原廠
App 也收到模擬封包，必須讓廣播端 MAC 末 3 bytes 等於目標 ID——Android 應用層無法指定廣播 MAC，需改用 ESP32
（`esp_base_mac_addr_set`）或 nRF52 這類可設定 MAC 的硬體，並一併廣播裝置名稱。

### 三、`deviceName` 不可為 null

```java
String deviceName = device2.getName();
Intrinsics.checkExpressionValueIsNotNull(deviceName, "deviceName");
```

`Intrinsics.checkExpressionValueIsNotNull` 在值為 null 時丟出 `IllegalStateException`
（`kotlin/jvm/internal/Intrinsics.java:82-86`），不是安靜跳過。裝置名稱常放在 scan response 而非
advertisement，clone 工具不一定會複製。任何沒有名稱的 BLE 廣播都能讓原廠 App 的掃描回呼拋例外。

### 四、長度檢查

```java
int length = bArr.length + 2;   // +2 是前置的 company id
if (length < 11) return;
```

即 manufacturer data 至少 9 bytes。BT1 實際有 17 bytes，這關不會擋。

### 掃描模式對比

| | 原廠 App | TPMS-advanced |
|---|---|---|
| ScanFilter | 2 個 service UUID | 6 個 service UUID（六種品牌） |
| 硬體 MAC 過濾 | 未使用 | 未使用 |
| 感測器身分來源 | `device.getAddress()`（軟體比對） | `payload[11..13]` |
| 掃描模式 | 寫死 `SCAN_MODE_LOW_LATENCY` | 首筆讀數用 `LOW_LATENCY`，之後降為 `SCAN_MODE_BALANCED` |

AOSP 中 `LOW_LATENCY` 的 scan window 等於 scan interval（5000ms／5000ms，100% duty cycle），收音機持續開啟；
`BALANCED` 約 2000ms／5000ms。兩邊的身分判定都在軟體層，成本都可忽略。

另有一項與背景監控相關的限制：**Android 8.1 起未帶 `ScanFilter` 的掃描在螢幕關閉時會被系統擋掉**，所以傳
filter 不只是省電考量，而是背景運作的必要條件。兩邊都有傳。
