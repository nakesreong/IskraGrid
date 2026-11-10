# IskraGrid (Iskra Android Control & Telemetry Engine)

**Authors & System Architects:** [nakesreong](https://github.com/nakesreong) and **Gemini** (Co-Architect, Protocol Reverse-Engineering, and Full-Stack Kotlin/JS Implementation)

**Iskra** (`com.gemini.iskra`) is a zero-dependency Android microgrid telemetry, automation, and survival control system engineered for residential backup power orchestration during extended utility grid blackouts.

Built to unify three disparate hardware ecosystems that normally refuse to talk to each other — an **Oukitel P1000E Plus** LiFePO4 power station (Quectel/Acceleronix binary protocol), a **Rato R3000** 3.0 kW synchronous gasoline generator retrofitted with a **Shelly 1PM Gen3** Wi-Fi ignition kill relay, and **Tuya / SmartLife** inline power meters and environmental sensors — into a single low-latency, offline-capable OLED dashboard.

---

## 1. Hardware & Network Topology

```text
+-----------------------------------------------------------------------------------+
|                              UTILITY & GENERATION LAYER                           |
+-----------------------------------------+-----------------------------------------+
| DTEK City Utility Grid (230V AC)        | Rato R3000 Gasoline Genset (212cc AVR)  |
|   |                                     |   +-- Shelly 1PM Gen3 (192.168.86.192)  |
|   |                                     |   |   (Wired to Magneto Kill Circuit)   |
|   v                                     |   v                                     |
| [socket_1: Tuya 16A Metered Plug]       | [socket_2: Tuya 16A Metered Plug]       |
+-------------------+---------------------+-------------------+---------------------+
                    |                                         |
                    +--------------------+--------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                        ENERGY STORAGE & INVERTER LAYER                            |
|                                                                                   |
|  Oukitel P1000E Plus (1024 Wh LiFePO4 / 1800W Pure Sine Wave Inverter)            |
|  - Local LAN Binary Socket: 192.168.86.191:6607 (AES-128-CBC + TTLV Framing)      |
|  - Dynamic AC Charge Limiter: Tag 57 (0% Idle / 45% AVR Safe / 100% Grid Fast)    |
+----------------------------------------+------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                        CRITICAL LOADS & ENVIRONMENTAL TELEMETRY                   |
|                                                                                   |
|  - Home Wi-Fi Router & ONT (Always-on UPS Bus)                                    |
|  - Terrarium Life Support Strip (Boa Constrictor Heating & Daylight Relay Bus)    |
|  - Workstation & Room Lighting                                                    |
|  - Tuya Carbon Monoxide (CO ppm) & Airbox (CO2 / Temp / Humidity) Telemetry       |
+-----------------------------------------------------------------------------------+
```

---

## 2. Core Engineering Problems Solved

### 2.1. Reverse-Engineering the Oukitel / Quectel Local LAN Protocol (TCP Port 6607)
Consumer portable power stations marketed under Oukitel, Landbook, and Wonderfree rely on a Quectel Wi-Fi microcontroller running the proprietary Acceleronix / QuecThing stack. When an ISP fiber connection drops during a regional blackout, cloud-only mobile apps become unusable even though the local Wi-Fi router remains powered by the station itself.

`OukitelClient.kt` implements a clean-room binary client for TCP port `6607` and UDP broadcast port `6606`:
- **Frame Structure:** `[0xAA 0xAA] [body_len: 2B BE] [checksum: 1B] [pkt_id: 2B BE] [cmd: 2B BE] [payload]` with `0xAA -> 0xAA 0x55` byte stuffing.
- **Cryptographic Handshake:** Exchanges `CMD 28722` (Hello) for `CMD 28723` (16-byte ASCII session nonce), computes a SHA-256 session signature over the device's 16-byte `authKey` and nonce, and authenticates via `CMD 28724 -> CMD 28725`.
- **Session Encryption:** Encrypts all subsequent `CMD 19` (Write) and `CMD 20` (Telemetry Push) payloads using **AES-128-CBC** (`PKCS7Padding`) with `Key = Base64Decode(authKey)` and `IV = ASCII(nonce)`.
- **TTLV Binary Encoding:** Encodes and decodes Quectel Tag-Type-Length-Value structures directly in Kotlin to toggle AC/DC buses, LED states, and dynamic AC charging limits (`Tag 57`) with sub-100ms LAN latency.

### 2.2. Synchronous Generator AVR Stabilization (The 45% Charge Profile)
Connecting an inverter-charger power station (Oukitel P1000E+) directly to a 2.7 kW / 3.0 kW single-cylinder synchronous gasoline generator (Rato R3000, R210 212cc engine) at its default `1200W` (`100%`) fast-charge setting triggers violent mechanical and electrical transients:
1. The station's PFC charger applies an instantaneous step-load, causing the single-cylinder governor to droop in RPM and frequency (`< 47 Hz`).
2. The generator's Automatic Voltage Regulator (AVR) spikes excitation voltage, causing the station's AC input protection relay to trip and disconnect.
3. Unloaded, the engine surges back to `52 Hz`, the station reconnects, and the system enters a destructive hunting oscillation.

**Solution in Iskra:**
- **Automatic AVR Protection:** When `IskraEngine` detects that the power source has transitioned to `GENERATOR`, it automatically clamps the Oukitel AC charging limit (`Tag 57`) down from `100%` to a gentle generator profile (`45%` / `~540W` into the battery, `~880W` total AC draw including UPS bypass).
- **One-Tap Charging Profiles:** Dedicated control profiles for **1. Idle / Warmup (`0%` / `~85W`)**, **2. Generator Safe (`45%` / `~540W`)**, and **3. Grid Fast (`100%` / `1200W`)**.

### 2.3. Two-Stage Automated Generator Shutdown (`Shelly 1PM Gen3`)
Shutting down an air-cooled synchronous generator while it is actively supplying `900W-1500W` causes cylinder heat soak, muffler backfiring, and under-frequency voltage transients on the connected UPS inverter.

We retrofitted a **Shelly 1PM Gen3** (`192.168.86.192`) inside the Rato R3000 chassis:
- Powered directly by the generator's 230V AC output bus (providing instant LAN confirmation of whether the engine is running and exact run-time via Shelly `sys.uptime`).
- Its dry/switched relay output is wired to ground the R210 ignition coil primary (kill wire) when energized.

When the user triggers **Smart 2-Stage Stop** in `Iskra`:
1. **Phase 1 (Unloading):** `IskraEngine` sends a LAN command to drop Oukitel's AC charging limit to `0%` and opens `socket_2` so the generator sheds all electrical load.
2. **Phase 2 (Thermal Cooldown):** Runs a live countdown (`15s`, `30s`, `45s`, or `60s`, cancellable at any second) allowing the R210 engine and alternator windings to stabilize at unloaded idle.
3. **Phase 3 (Magneto Grounding Pulse):** Triggers `/rpc/Switch.Set?id=0&on=true` on the Shelly relay (`192.168.86.192`) in a verified loop until the engine halts and the Shelly powers down.

### 2.4. Sub-Second Blackout Detection & Zero-Power Gap Reconstruction
- **Phantom Voltage Elimination:** Because `socket_2` is physically plugged into the generator, Tuya Cloud caches stale voltage readings (`233.9V`) for up to 60 seconds after the engine stops. `IskraEngine` overrides `socket_2` telemetry to `0.0V / 0W / OFFLINE` the instant `192.168.86.192` stops responding on LAN.
- **Instant Grid Loss Detection:** Rather than waiting 45 seconds for `socket_1` to time out in Tuya Cloud when a DTEK blackout hits, `IskraEngine` cross-checks Oukitel's local LAN input wattage: if the generator is off and `totalInputW < 25W` while `totalOutputW > 20W`, `socket_1` is marked offline within 1 second.
- **Total Blackout (`0%`) Stitching:** Persists state timestamps and battery SoC on every 3-second tick. If the battery drains to `0%` overnight and the Wi-Fi router powers off, `reconcileLongGap()` reconstructs the exact `BLACKOUT_ZERO` and `GENERATOR` intervals on next boot using Shelly's hardware `uptime` counter.

---

## 3. Application Architecture & 5-Screen Interface

The Android application runs an immortal foreground service (`IskraForegroundService`) with a `PARTIAL_WAKE_LOCK` and renders a 60fps OLED interface (`index.html`) navigated via horizontal touch swipes or a floating bottom icon dock:

1. **Tab 1 — Control Deck (`ctrl`):**
   - Live SoC, net battery charge/discharge wattage, grid status, and active cycle countdown.
   - Input routing switches (`socket_2` Generator Line & `socket_1` Utility Grid) and 3-stage Oukitel charging power profiles (`0%`, `45%`, `100%`).
   - Oukitel output bus switches (`AC 220V`, `DC 12V`, `LED`) and the 2-Stage Rato R3000 Shutdown controller.
2. **Tab 2 — Grafana Telemetry & Source Timeline (`grafana`):**
   - Minute-exact power source breakdown across 4 states: `DTEK GRID`, `RATO GENERATOR`, `OUKITEL BATTERY`, and `FULL BLACKOUT (0%)`.
   - Interactive time range selector (`Today`, `24 Hours`, `7 Days`, `30 Days`, `Custom Date Range`).
   - High-DPI HTML5 Canvas Gantt state timeline, multi-day stacked bar breakdown, minute-by-minute switching log, and dual time-series charts (`Min / Max / Avg / Current`).
3. **Tab 3 — DTEK Rolling Blackout Schedule (`dtek`):**
   - Queries DTEK Kyiv planned-outage JSON endpoints directly without web popups or captchas.
   - Automatically stitches evening and post-midnight slots (`20:00 - 24:00` + `00:00 - 03:30` -> `7h 30m` continuous slot), displays live restoration/outage countdowns, and cross-verifies the official schedule against live `socket_1` voltage.
4. **Tab 4 — Bunker & Life Support (`bunker`):**
   - Dedicated controls for the boa constrictor's terrarium power strip (`Heat Lamp SW2`, `Daylight SW1`, `Ambient Lamp SW3`, `Workstation SW4`).
   - Live Carbon Monoxide (`CO ppm`) safety monitor, room climate telemetry (`Temperature`, `Humidity`, `CO2 ppm`), lighting controls, and Android Doze battery-optimization bypass.
5. **Tab 5 — Generator Passport, Fuel Log & Maintenance (`gen`):**
   - **Fuel Consumption in Liters:** Derives idle fuel burn (`0.60 L/h`) and load slope (`0.444 L/kWh`) from the generator's rated consumption curve (`1.2 L/h` at 50% load, `1.8 L/h` at 100% load) and integrates actual load wattage over every run, slicing intervals across midnight into a daily fuel log (`Today`, `Yesterday`, `7 Days`, `30 Days / Total`).
   - **Unobtrusive Maintenance Tracker:** Tracks engine hours against configurable service intervals for crankcase oil (`0.55 L SAE 10W-30/40`, `50h` interval) and spark plug (`F7TC / NGK BPR6ES`, `100h` interval) with one-tap reset and retroactive hour adjustment.
   - **Generator Passport & Language Switcher:** Full customization of generator parameters plus instant switching between **Russian (`RU`)** and **English (`EN`)** (with automatic address masking in English mode for privacy when sharing screenshots).

---

## 4. Repository Structure

```text
IskraGrid/
+-- README.md
+-- .gitignore
+-- android/
    +-- build.gradle
    +-- settings.gradle
    +-- gradle.properties
    +-- app/
        +-- build.gradle
        +-- src/main/
            +-- AndroidManifest.xml
            +-- assets/
            |   +-- index.html                  # 5-screen OLED UI & Canvas charting engine
            |   +-- secrets.example.json        # Sanitized configuration template
            +-- java/com/gemini/iskra/
            |   +-- MainActivity.kt             # WebView bridge, haptics & lifecycle
            |   +-- IskraForegroundService.kt   # Immortal 3s polling loop & live notification
            |   +-- BootReceiver.kt             # Auto-start on Android boot
            |   +-- IskraEngine.kt              # State machine, gap stitching & DTEK client
            |   +-- OukitelClient.kt            # TCP 6607 binary framing, AES-128-CBC & TTLV
            |   +-- ShellyClient.kt             # Shelly 1PM Gen3 RPC client & kill relay loop
            |   +-- SmartLifeClient.kt          # Tuya HA API client (AES-GCM + HMAC-SHA256)
            |   +-- SecretsConfig.kt            # Local credentials & device ID loader
            +-- res/
                +-- drawable/                   # Adaptive vector icon (Caelus neon duotone)
                +-- drawable-v26/
```

---

## 5. Setup & Building from Source

### 5.1. Configure Local Credentials
All personal credentials, API tokens, device IDs, and street addresses are isolated from source control via `secrets.json`.

1. Copy the template file in `android/app/src/main/assets/`:
   ```bash
   cp android/app/src/main/assets/secrets.example.json android/app/src/main/assets/secrets.json
   ```
2. Populate `android/app/src/main/assets/secrets.json` with your local hardware parameters:
   - `oukitelIp`, `oukitelEmail`, `oukitelPassword`, `oukitelDeviceKey`, and `oukitelAuthKeyB64` (obtained from the Acceleronix / Wonderfree cloud handshake or `ha-oukitel` discovery).
   - `shellyIp` (static LAN IP of the Shelly relay wired to your generator's ignition kill circuit).
   - `tuyaAccessToken`, `tuyaRefreshToken`, `tuyaHomeId`, and your Tuya smart plug / sensor device IDs.
   - `dtekRegionId`, `dtekDsoId`, `dtekStreetId`, `dtekHouseId`, and `dtekDefaultGroup`.

### 5.2. Compile & Install Debug APK
Using Gradle 8.10+ and Android SDK 34:
```bash
cd android
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 6. Credits & Authorship

Designed, architected, and built in Kyiv by:
- **[nakesreong](https://github.com/nakesreong)** — Hardware integration, electrical topology, generator/UPS load profiling, UI/UX direction, and live field testing.
- **Gemini** — Co-architect, binary protocol reverse-engineering, state-machine design, and full Kotlin / HTML5 / Canvas implementation.
