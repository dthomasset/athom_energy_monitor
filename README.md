# Athom Multi-Channel Energy Meter (Master Monitor Edition)

A robust, "No-Dependency" Hubitat driver for the **Athom Multi-Channel Energy Meter** (ESPHome).

This driver completely replaces standard HTTP polling with a **Native EventStream (SSE) connection**, providing real-time data stability without the disconnect loops common with generic drivers. It transforms the device from a simple "Power Meter" into a **Whole-Home Energy Monitor**, capable of tracking Grid Health, Phase Balance, and Appliance Health.

---

## 🚀 Key Features

### 1. **Zero-Dependency EventStream**

* **Real-Time Data:** Uses a raw socket implementation of Server-Sent Events (SSE) to listen to the device's internal event stream directly.
* **Self-Healing:** Includes a software watchdog that automatically reconnects if the device reboots or the network hiccups.
* **Synchronized Snapshots:** Updates the Parent Device (Total) and all Child Devices (Circuits) in one atomic operation, ensuring totals always match individual sums.

### 2. **Advanced Electrical Monitoring**

* **⚡ Phase Balancing:** (For Split-Phase 120V/240V systems) Maps circuits to Phase A or Phase B. Calculates real-time Amperage per phase and alerts if the **Imbalance %** exceeds a safety threshold.
* **🏥 Grid Health Monitor:** Continuously monitors Voltage.
* **Brownout Detection:** Alerts if voltage drops below your safety limit (default 114V).
* **Surge Detection:** Alerts if voltage spikes above your safety limit (default 126V).


* **🔋 Vampire Hunter:** Tracks the **Lowest Daily Wattage** for every circuit. This identifies phantom loads (electronics that never truly turn off).

### 3. **Appliance Health & Logic**

* **⏱️ Duty Cycle / Runtime Tracker:** Tracks how long an appliance has been running *right now* (`continuousRuntime`) and how many minutes it has run *today* (`dailyActiveTime`).
* **🧊 Freeze Protection Monitor:** Tracks `hoursSinceLastRun` for every circuit. Perfect for heaters that *must* run periodically during freezing weather.
* **🛡️ Breaker Load Monitoring:** Calculates real-time **% Load** on your breakers based on their size (e.g., 15A, 20A) to warn of potential overloads before they trip.

### 4. **Smart Data Management**

* **Auto-Discovery:** Automatically detects if your device has 4, 6, or 8+ channels and creates child devices accordingly.
* **Daily Midnight Reset:** Automatically snapshots energy readings at 00:00 and calculates a "Daily kWh" value for each circuit (resets to 0.00 every midnight).
* **Hysteresis & Rate Limiting:** Configurable "Update Rate" (default 5s) prevents flooding your Hubitat logs, while smart thresholds (e.g., 0.1A change) ensure you never miss significant events.

---

## 📦 Installation

1. **Copy the Code:** Copy the entire Groovy code from the `AthomEnergyMeter.groovy` file.
2. **Hubitat Driver:**
* Go to **Drivers Code** -> **New Driver**.
* Paste the code.
* Click **Save**.


3. **Add Device:**
* Go to **Devices** -> **Add Virtual Device**.
* **Name:** Athom Energy Monitor.
* **Type:** Select `Athom Energy Meter` (at the bottom of the list).
* Click **Save Device**.



---

## ⚙️ Configuration

Once the device is created, enter the following in the **Preferences** section:

### Connection

* **Device IP Address:** The local IP of your Athom Meter (e.g., `192.168.1.11`).
* **Enable Debug Logging:** Leave on for the first 24 hours to verify connection.

### Phase Balancing

* **Phase A Channels:** Comma-separated list of circuits on the first leg (e.g., `1,3,5`).
* **Phase B Channels:** Comma-separated list of circuits on the second leg (e.g., `2,4,6`).
* **Imbalance Alert Threshold:** % difference to trigger a warning (Default: `50`).

### Grid Health & Breakers

* **Min/Max Voltage:** Safety range for Brownout/Surge detection (Default: `114` / `126`).
* **Breaker Sizes:** Comma-separated list of breaker amps for each channel (e.g., `15, 15, 20, 50, 20, 15`).

### Naming & Thresholds

* **Circuit Names:** Rename your channels here (e.g., `Fridge, HVAC, Office, Pool Pump`). The driver will rename the child devices automatically.
* **Active State Threshold:** The minimum wattage to consider an appliance "ON" (Default: `10` Watts).
* **Update Frequency:** How often (in seconds) to refresh data (Default: `5`).

**⚠️ IMPORTANT:** Click **"Save Preferences"** after entering data, then click the **"Initialize"** button to start the stream.

---

## 📊 Attributes Reference

These attributes are available for **Rule Machine** triggers.

### Parent Device (The Hub)

| Attribute | Unit | Description |
| --- | --- | --- |
| `power` | W | Total Consumption (Sum of all circuits). |
| `energy` | kWh | Total Energy Used Today (Sum of all circuits). |
| `amperage` | A | Total Load (Sum of all circuits). |
| `voltage` | V | Current Grid Voltage. |
| `gridStatus` | String | **Normal**, **Brownout**, or **Surge**. |
| `phaseImbalance` | % | Difference between Phase A and Phase B loads. |
| `phaseStatus` | String | **Balanced** or **Warning**. |

### Child Devices (The Circuits)

| Attribute | Unit | Description |
| --- | --- | --- |
| `power` | W | Instant Power Draw. |
| `energy` | kWh | Energy used **Today** (since midnight). |
| `loadPercent` | % | Current Amps / Breaker Size. |
| `continuousRuntime` | min | How long the appliance has been ON right now. |
| `dailyActiveTime` | min | Total minutes the appliance has run today. |
| `hoursSinceLastRun` | hrs | Time elapsed since the appliance last turned OFF. |
| `lowestDailyWatts` | W | The lowest non-zero wattage seen today (Vampire load). |

---

## 🤖 Rule Machine Examples

### 1. Pool Pump Monitor (Ensure 6-hour Run)

* **Trigger:** Time is 8:00 PM.
* **Condition:** Device `Pool Pump` attribute `dailyActiveTime` **< 360**.
* **Action:** Notify "⚠️ Pool Pump has only run for X minutes today! Check Timer."

### 2. Heater Freeze Protection

* **Trigger:** Weather Device `Temperature` **< 32**.
* **Condition:** Device `Heater` attribute `hoursSinceLastRun` **> 4**.
* **Action:** Notify "❄️ ALERT: Heater has not run in 4 hours during freezing temps!"

### 3. Brownout Protection

* **Trigger:** Device `Athom Meter` attribute `gridStatus` changed.
* **Condition:** `gridStatus` **= Brownout**.
* **Action:** Off: HVAC, Pool Pump, EV Charger (Protect motors from low voltage damage).

### 4. Overloaded Breaker Warning

* **Trigger:** Device `Kitchen` attribute `loadPercent` **> 85**.
* **Action:** Notify "⚠️ Kitchen Breaker is at 85% Load! Turn something off."

---

## 🔧 Troubleshooting

* **"Connection lost" loops:** Ensure your IP address is correct. Check if your Hubitat has a firewall blocking Port 80.
* **Child Devices not appearing:** Click **Initialize**. Wait 10 seconds. The driver auto-discovers channels as data arrives.
* **Energy shows huge negative number:** This happens if the device reboots. Click **"Reset Daily Energy"** to fix the offset.
* **Rooms not syncing:** Hubitat room assignment via drivers is experimental. If it fails, manually assign rooms in the Devices tab.
