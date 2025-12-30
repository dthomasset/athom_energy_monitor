# Athom Multi-Channel Energy Meter (Hubitat Driver)

**Author:** Dan Thomasset  
**Version:** 2025.12.29

A comprehensive, zero-dependency Hubitat driver for **Athom Multi-Channel Energy Meters** (ESPHome).

Unlike generic drivers that rely on frequent HTTP polling—which can destabilize the device or flood the network—this driver establishes a **Native EventStream (SSE)** connection. It listens to the device's internal event bus in real-time, providing sub-second updates, rock-solid stability, and advanced logic features that transform a simple power meter into a whole-home energy monitor.

This driver supports auto-discovery for arbitrary channel counts (4, 6, 8, etc.).

---

## Key Features

### 1. Intelligent Grid & Phase Monitoring
* **Phase Balancing (Split-Phase):** For North American 120/240V homes, monitors the load on Phase A vs. Phase B. It calculates the **Imbalance %** in real-time to help avoid overloading a single leg or overheating the neutral wire.
* **Grid Health Watchdog:** Continuously monitors voltage quality.
    * **Brownout Detection:** Alerts if voltage drops below a configurable limit (e.g., < 114V), protecting sensitive motors and compressors.
    * **Surge Detection:** Alerts if voltage spikes dangerously high (e.g., > 126V).
* **Vampire Hunter:** Tracks the **Lowest Daily Wattage** observed on each circuit. This value reveals "Ghost Load"—power used by electronics that are "off" but still plugged in.

### 2. Appliance Health & Logic
* **Runtime Tracker:** Tracks two key metrics for every circuit:
    * `continuousRuntime`: How long the appliance has been ON right now (in minutes).
    * `dailyActiveTime`: Total minutes the appliance has run since midnight.
* **Idle Monitor (Freeze Protection):** Tracks `hoursSinceLastRun`. Critical for ensuring heating systems or circulation pumps run periodically during freezing weather.
* **Breaker Load Monitoring:** You define the breaker size (e.g., 20A) for each channel. The driver calculates **% Load** and alerts if a circuit is nearing its trip point.

### 3. Data Integrity & Sync
* **Auto-Discovery:** Automatically detects the number of channels reported by the hardware and creates the necessary child devices.
* **Atomic Snapshots:** Updates the Parent Device (Totals) and all Child Devices (Circuits) in a single, synchronized step. Total Power always equals the sum of the parts.
* **Midnight Reset:** Automatically snapshots hardware counters at 00:00 to provide a clean "Daily kWh" count that resets every day.

---

## Installation Instructions

1.  **Install Driver Code:**
    * Open your Hubitat Web Interface.
    * Navigate to **Developer** -> **Drivers Code**.
    * Click **New Driver**.
    * Paste the content of `AthomEnergyMeter.groovy`.
    * Click **Save**.

2.  **Create Device:**
    * Navigate to **Devices** -> **Add Device**.
    * Select **Virtual Device**.
    * **Device Name:** Athom Energy Monitor.
    * **Type:** Scroll to the bottom and select **Athom Energy Meter** (User).
    * Click **Save Device**.

3.  **Setup & Discovery:**
    * Open the newly created device page.
    * Enter your **IP Address** in the Preferences.
    * Click **Save Preferences**.
    * Click the **Initialize** button. The driver will connect and automatically discover and create child devices for all available channels.

---

## Configuration & Settings

### Section 1: Connection & Hardware
| Setting | Description |
| :--- | :--- |
| **Device IP Address** | The local IP address of your Athom device (e.g., `192.168.1.50`). |
| **Enable Debug Logging** | Write detailed connection logs to the Hubitat log. Recommended for setup; disable later to reduce noise. |
| **Temperature Unit** | Select `Fahrenheit`, `Celsius`, or `Kelvin`. The driver automatically converts the device's raw reading. |

### Section 2: Circuit Configuration
| Setting | Description |
| :--- | :--- |
| **Circuit Names** | Comma-separated list to rename your channels. <br>_Example:_ `Fridge, HVAC, Office, Pool Pump` |
| **Breaker Sizes** | Comma-separated list of Amps for each circuit. Used for Load % calculations. <br>_Example:_ `15, 15, 20, 50, 20, 15` |
| **Phase A Channels** | Channels connected to Leg 1 (Black Wire). Default: `1,3,5`. |
| **Phase B Channels** | Channels connected to Leg 2 (Red Wire). Default: `2,4,6`. |

### Section 3: Safety & Logic
| Setting | Description |
| :--- | :--- |
| **Brownout Threshold** | Trigger `gridStatus: Brownout` if voltage drops below this (Default: `114`V). |
| **Surge Threshold** | Trigger `gridStatus: Surge` if voltage rises above this (Default: `126`V). |
| **Phase Imbalance %** | Trigger `phaseStatus: Warning` if the difference between Phase A and B loads exceeds this %. <br>**Set to -1 to Disable Phase Monitoring.** |
| **Appliance 'On' Threshold** | Minimum Watts for a device to be considered "Running". Used for Duty Cycle and Runtime metrics (Default: `10`W). |

### Section 4: Reporting Sensitivity
| Setting | Description |
| :--- | :--- |
| **Global Update Rate** | **(Important)** The minimum time (in seconds) between updates. Prevents the driver from flooding your hub database if power fluctuates rapidly. Default: `5`s. |
| **Change Threshold** | The sensor must change by at least this amount to trigger an update. Applies to Volts, Amps, Watts, and Hz. Default: `0.1`. |

---

## Rule Machine Examples

### 1. Pool Pump "Under-Run" Alert
**Goal:** Ensure your pool pump runs for at least 6 hours (360 minutes) a day to maintain water chemistry.
* **Trigger:** Time is 8:00 PM.
* **Condition:** Device `Pool Pump` attribute `dailyActiveTime` is **< 360**.
* **Action:** Notify Phone: *"Alert: Pool Pump has only run for %value% minutes today. Check timer!"*

### 2. Heater Freeze Protection
**Goal:** Ensure the heater runs periodically when it is freezing outside to prevent pipes from bursting.
* **Trigger:** Weather Device `Temperature` turns **< 32°F**.
* **Condition:** Device `Heater` attribute `hoursSinceLastRun` is **> 4** (Hasn't run in 4 hours).
* **Action:**
    1.  Notify Phone: *"CRITICAL: Heater hasn't run in 4 hours!"*
    2.  Switch `Heater` **On** (Force run).

### 3. Grid Health / Brownout Protection
**Goal:** Automatically shut off sensitive heavy motors (AC Compressor, Shop Tools) if voltage sags, as low voltage causes high heat and motor failure.
* **Trigger:** Device `Athom Meter` attribute `gridStatus` **changed**.
* **IF** `gridStatus` is **"Brownout"**:
    * **Action:** Turn **Off** `AC Thermostat`, `Shop Air Compressor`.
    * **Action:** Notify: *"Grid Brownout detected! Protection Mode Active."*
* **ELSE-IF** `gridStatus` is **"Normal"**:
    * **Action:** Notify: *"Grid restored to normal."*

### 4. Overloaded Circuit Warning
**Goal:** Warn if a specific circuit is dangerously close to tripping its breaker.
* **Trigger:** Device `Kitchen` attribute `loadPercent` becomes **> 90**.
* **Action:** Notify: *"Kitchen Breaker at 90% Load! Turn off appliances immediately."*

### 5. "Did I leave the Iron on?"
**Goal:** Detect if a high-power resistive load has been running too long.
* **Trigger:** Device `Iron/Bath` attribute `continuousRuntime` becomes **> 60**.
* **Action:** Notify: *"Iron has been on for 60 minutes. Did you forget it?"*
* **Action:** Turn **Off** `Smart Plug - Iron`.
