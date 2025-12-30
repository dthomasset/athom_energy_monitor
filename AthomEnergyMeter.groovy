/**
 * Athom Energy Meter
 *
 * Author: Dan Thomasset <dthomas@gmail.com>
 * Date: 2025-12-29
 *
 * Description:
 * A comprehensive energy monitoring suite for the Athom 6-Channel (ESPHome) Meter.
 * Transforms raw sensor data into actionable home intelligence via a native, 
 * zero-dependency EventStream connection.
 *
 * CORE FEATURES:
 * 1. INTELLIGENT MONITORING
 * - Grid Health Watchdog: Detects Brownouts (Low Voltage) and Surges (High Voltage).
 * - Phase Balancing: Monitors Split-Phase loads and calculates Imbalance %.
 * - Vampire Hunter: Tracks the "Lowest Daily Wattage" to identify phantom loads.
 *
 * 2. APPLIANCE & SAFETY LOGIC
 * - Breaker Monitoring: Calculates real-time Breaker Load % based on configurable amp ratings.
 * - Runtime Tracker: Monitors "Continuous Runtime" and "Daily Active Time".
 * - Idle Monitor: Tracks "Hours Since Last Run" (Freeze Protection).
 *
 * 3. DATA MANAGEMENT
 * - Native EventStream: Raw socket SSE for stable, sub-second reporting.
 * - Auto-Discovery: Automatically detects channel count and creates child devices.
 * - Daily Reset: Snapshots and resets Energy/Runtime metrics at midnight.
 * - Smart Synchronization: Updates Parent and Child devices in a single atomic snapshot.
 */

metadata {
    definition (name: "Athom Energy Meter", namespace: "user", author: "Dan Thomasset") {
        capability "Initialize"
        capability "Refresh"
        capability "Polling"
        capability "VoltageMeasurement"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "Sensor"
        capability "CurrentMeter" 
        
        attribute "frequency", "number"
        attribute "temperature", "number"
        attribute "uptime", "string"
        attribute "connectionState", "string"
        
        // Safety Status Attributes
        attribute "gridStatus", "string"   // Normal, Brownout, Surge
        attribute "phaseStatus", "string"  // Balanced, Warning, Disabled
        attribute "phaseImbalance", "number" 
        
        // Phase Details
        attribute "phaseA_Amps", "number"
        attribute "phaseB_Amps", "number"
        
        command "resetDailyEnergy"
        command "disconnect"
        command "forceRenaming"
        command "syncRooms"
    }

    preferences {
        // --- SECTION 1: CONNECTION & HARDWARE ---
        section("Connection & Hardware") {
            input name: "ipAddress", type: "text", title: "Device IP Address", required: true
            input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: true
            input name: "tempScale", type: "enum", title: "Temperature Unit", options: ["Fahrenheit", "Celsius", "Kelvin"], defaultValue: "Fahrenheit"
        }
        
        // --- SECTION 2: CIRCUIT CONFIGURATION ---
        section("Circuit Configuration") {
            input name: "channelNames", type: "text", title: "Circuit Names", description: "Comma separated (e.g: Fridge, HVAC, Office...)"
            input name: "breakerSizes", type: "text", title: "Breaker Sizes (Amps)", description: "Comma separated (e.g: 15, 15, 20...)"
        }
        
        // --- SECTION 3: SAFETY & LOGIC ---
        section("Safety & Logic") {
            // Phase Config & Thresholds moved here to be grouped together
            input name: "phaseA_Channels", type: "text", title: "Phase A Channels (Split-Phase)", defaultValue: "1,3,5"
            input name: "phaseB_Channels", type: "text", title: "Phase B Channels (Split-Phase)", defaultValue: "2,4,6"
            input name: "imbalanceThreshold", type: "number", title: "Phase Imbalance Threshold (%)", description: "Triggers 'Warning' status above this. Set to -1 to Disable.", defaultValue: -1
            
            // Grid Thresholds
            input name: "voltageLowThreshold", type: "number", title: "Brownout Threshold (V)", description: "Triggers 'Brownout' status below this.", defaultValue: 114
            input name: "voltageHighThreshold", type: "number", title: "Surge Threshold (V)", description: "Triggers 'Surge' status above this.", defaultValue: 126
            
            // Appliance Logic
            input name: "activeThreshold", type: "number", title: "Appliance 'On' Threshold (W)", description: "Watts required to count as 'Running' for metrics.", defaultValue: 10
        }
        
        // --- SECTION 4: REPORTING ---
        section("Reporting Sensitivity") {
            input name: "sensorHysteresis", type: "number", title: "Global Update Rate (seconds)", description: "Minimum time between updates (Anti-Flood)", defaultValue: 5
            input name: "minReportingChangeThreshold", type: "decimal", title: "Change Threshold", description: "Min change for Volts, Amps, Watts, Hz", defaultValue: 0.1
        }
    }
}

// =============================================================================
// Lifecycle
// =============================================================================

void updated() {
    disconnect()
    unschedule()
    runIn(1, "initialize")
    runIn(2, "renameChildren")
    runIn(3, "syncRooms")
    
    schedule("0 0 0 * * ?", midnightMaintenance)
    
    // Init Memory
    if (!state.power) state.power = [:]
    if (!state.energy) state.energy = [:]
    if (!state.energyOffset) state.energyOffset = [:]
    if (!state.current) state.current = [:]
    if (!state.lastUpdate) state.lastUpdate = [:]
    
    // Feature Memory
    if (!state.lowestWatts) state.lowestWatts = [:]
    if (!state.activeStart) state.activeStart = [:] 
    if (!state.dailyActive) state.dailyActive = [:] 
    if (!state.lastRunTime) state.lastRunTime = [:] 
    state.lastDutyCheck = now()
}

void initialize() {
    disconnect()
    pauseExecution(1000)
    
    if (ipAddress) {
        if (logEnable) log.info "Connecting to EventStream: http://${ipAddress}/events"
        sendEvent(name: "connectionState", value: "connecting")
        interfaces.eventStream.connect("http://${ipAddress}/events", [pingInterval: 10, readTimeout: 60])
        runEvery1Minute(watchdog)
    }
}

void watchdog() {
    if (device.currentValue("connectionState") != "connected") {
        if (logEnable) log.warn "Watchdog: Stream disconnected. Attempting reconnect..."
        initialize()
    }
}

void disconnect() {
    try { interfaces.eventStream.close() } catch(e) {}
    try { interfaces.rawSocket.close() } catch(e) {}
    sendEvent(name: "connectionState", value: "disconnected")
}

// =============================================================================
// Midnight Maintenance
// =============================================================================

void midnightMaintenance() {
    if (logEnable) log.info "Performing Midnight Maintenance..."
    resetDailyEnergy()
    state.lowestWatts = [:]
    state.dailyActive = [:]
    syncSystem()
}

// =============================================================================
// Data Processing
// =============================================================================

void parse(String description) {
    def jsonStr = description.startsWith("data:") ? description.substring(5).trim() : description
    if (!jsonStr || jsonStr == "ping" || jsonStr == "{}") return

    try {
        def json = parseJson(jsonStr)
        if (json.id) processJson(json)
    } catch (e) { }
}

void processJson(Map json) {
    String id = json.id.toString()
    def rawValue = json.value
    def threshold = minReportingChangeThreshold ?: 0.1
    
    // 1. Global
    if (id.contains("voltage")) {
        checkGridHealth(rawValue)
        updateSensor("voltage", rawValue, "V", threshold)
    }
    else if (id.contains("frequency")) updateSensor("frequency", rawValue, "Hz", threshold)
    else if (id.contains("temperature")) processTemperature(rawValue)
    else if (id.contains("uptime")) sendEvent(name: "uptime", value: json.state)
    
    // 2. Channels
    else if (id =~ /(power|current|energy|amperage)_(\d+)/) {
        if (!id.contains("internal")) {
            def matcher = (id =~ /(power|current|energy|amperage)_(\d+)/)
            if (matcher.find()) {
                String type = matcher.group(1).toLowerCase()
                String channel = matcher.group(2)
                
                ensureChildExists(channel)
                updateState(channel, type, rawValue)
                syncSystem() 
            }
        }
    }
}

void updateState(String channel, String type, def rawValue) {
    def val = rawValue
    if (val instanceof Number) val = Math.abs(val.toDouble())
    
    if (type == "power") {
        if (!state.power) state.power = [:]
        state.power[channel] = val
    }
    else if (type == "energy") {
        if (!state.energy) state.energy = [:]
        state.energy[channel] = val 
    }
    else if (type == "current" || type == "amperage") {
        if (!state.current) state.current = [:]
        state.current[channel] = val
    }
}

// =============================================================================
// System Sync (The Brain)
// =============================================================================

void syncSystem() {
    if (isRateLimited("SystemSync")) return
    
    def totalPower = 0.0
    def totalEnergy = 0.0
    def totalCurrent = 0.0
    def nowMs = now()
    def threshold = minReportingChangeThreshold ?: 0.1
    
    // Duty Cycle Timing
    def lastTime = state.lastDutyCheck ?: nowMs
    def timeDeltaMinutes = (nowMs - lastTime) / 60000.0
    state.lastDutyCheck = nowMs
    
    // Phase Config Parsing
    def phaseA = phaseA_Channels ? phaseA_Channels.split(",").collect{it.trim()} : ["1","3","5"]
    def phaseB = phaseB_Channels ? phaseB_Channels.split(",").collect{it.trim()} : ["2","4","6"]
    def ampsA = 0.0
    def ampsB = 0.0
    def phaseEnabled = (imbalanceThreshold != null && imbalanceThreshold >= 0)
    
    // Breaker Config Parsing
    def breakers = []
    if (breakerSizes) breakers = breakerSizes.split(",").collect { it.trim().isNumber() ? it.toDouble() : 0.0 }
    
    // --- CHILD LOOP ---
    getChildDevices().each { child ->
        String dni = child.deviceNetworkId
        String c = dni.tokenize("CT").last()
        int chIdx = c.toInteger()
        
        def p = (state.power && state.power[c]) ? state.power[c] : 0.0
        def rawE = (state.energy && state.energy[c]) ? state.energy[c] : 0.0
        def a = (state.current && state.current[c]) ? state.current[c] : 0.0
        def dailyE = getDailyEnergy(c, rawE)
        
        totalPower += p
        totalEnergy += dailyE
        totalCurrent += a
        
        // Phase Allocation
        if (phaseEnabled) {
            if (phaseA.contains(c)) ampsA += a
            else if (phaseB.contains(c)) ampsB += a
        }
        
        // 1. VAMPIRE HUNTER
        if (!state.lowestWatts) state.lowestWatts = [:]
        def currentLowest = state.lowestWatts[c]
        if ((currentLowest == null) || (p > 0 && p < currentLowest)) {
            state.lowestWatts[c] = p
            checkAndSend(child, "lowestDailyWatts", p, "W", 0.5)
        } else if (currentLowest != null) {
             if (child.currentValue("lowestDailyWatts") == null) checkAndSend(child, "lowestDailyWatts", currentLowest, "W", 0.5)
        }

        // 2. RUNTIME MONITOR
        def activeLimit = activeThreshold ?: 10
        def isApplianceOn = (p >= activeLimit)
        
        if (isApplianceOn) {
            if (!state.activeStart) state.activeStart = [:]
            if (!state.activeStart[c]) state.activeStart[c] = nowMs
            
            def durationMs = nowMs - state.activeStart[c]
            def minutes = Math.round((durationMs / 60000) * 10) / 10
            checkAndSend(child, "continuousRuntime", minutes, "min", 1.0)
            
            if (!state.dailyActive) state.dailyActive = [:]
            def currentDaily = state.dailyActive[c] ?: 0.0
            state.dailyActive[c] = currentDaily + timeDeltaMinutes
            
            if (!state.lastRunTime) state.lastRunTime = [:]
            state.lastRunTime[c] = nowMs
            checkAndSend(child, "hoursSinceLastRun", 0.0, "hrs", 0.1)
            
        } else {
            if (state.activeStart && state.activeStart[c]) {
                state.activeStart.remove(c)
                checkAndSend(child, "continuousRuntime", 0, "min", 0.1)
            }
            
            if (!state.lastRunTime) state.lastRunTime = [:]
            if (state.lastRunTime[c]) {
                def diffMs = nowMs - state.lastRunTime[c]
                def hours = Math.round((diffMs / 3600000.0) * 100) / 100 
                checkAndSend(child, "hoursSinceLastRun", hours, "hrs", 0.5)
            }
        }
        
        if (state.dailyActive && state.dailyActive[c]) {
             def dailyMins = Math.round(state.dailyActive[c] * 10) / 10
             checkAndSend(child, "dailyActiveTime", dailyMins, "min", 1.0)
        }

        // 3. BREAKER LOAD
        if (breakers.size() >= chIdx) {
            def limit = breakers[chIdx - 1]
            if (limit > 0) {
                def loadPct = (a / limit) * 100.0
                checkAndSend(child, "loadPercent", loadPct, "%", 1.0)
            }
        }
        
        checkAndSend(child, "power", p, "W", threshold)
        checkAndSend(child, "energy", dailyE, "kWh", 0.001) // Energy always detailed
        checkAndSend(child, "amperage", a, "A", threshold)
    }
    
    // --- PARENT UPDATES ---
    updateSensor("power", totalPower, "W", threshold, true)
    updateSensor("energy", totalEnergy, "kWh", 0.001, true)
    updateSensor("amperage", totalCurrent, "A", threshold, true)
    
    // --- PHASE BALANCE ---
    if (phaseEnabled) {
        calculatePhaseBalance(ampsA, ampsB)
    } else {
        if (device.currentValue("phaseStatus") != "Disabled") sendEvent(name: "phaseStatus", value: "Disabled")
    }
}

void calculatePhaseBalance(def a, def b) {
    updateSensor("phaseA_Amps", clamp(a), "A", 0.1, true)
    updateSensor("phaseB_Amps", clamp(b), "A", 0.1, true)
    
    def max = Math.max(a, b)
    def diff = Math.abs(a - b)
    def pct = (max > 0) ? ((diff / max) * 100.0) : 0.0
    
    updateSensor("phaseImbalance", clamp(pct), "%", 1.0, true)
    
    def warnLevel = imbalanceThreshold ?: 50
    String pStatus = (pct > warnLevel && max > 5) ? "Warning" : "Balanced" 
    
    if (device.currentValue("phaseStatus") != pStatus) {
        sendEvent(name: "phaseStatus", value: pStatus)
        if (pStatus == "Warning") log.warn "PHASE IMBALANCE: ${pct.round(1)}% (A:${a}A vs B:${b}A)"
    }
}

// --- GRID HEALTH MONITOR ---
void checkGridHealth(def voltage) {
    def v = voltage.toDouble()
    def min = voltageLowThreshold ?: 114
    def max = voltageHighThreshold ?: 126
    
    String status = "Normal"
    if (v < min) status = "Brownout"
    else if (v > max) status = "Surge"
    
    if (device.currentValue("gridStatus") != status) {
        if (status != "Normal") log.warn "GRID ALERT: Voltage is ${v}V (${status})!"
        sendEvent(name: "gridStatus", value: status)
    }
}

// =============================================================================
// Utilities & Helpers
// =============================================================================

void resetDailyEnergy() {
    if (state.energy) {
        state.energy.each { channel, val ->
            if (!state.energyOffset) state.energyOffset = [:]
            state.energyOffset[channel] = val
        }
    }
    syncSystem()
}

def getDailyEnergy(String channel, def rawValue) {
    if (!state.energyOffset) state.energyOffset = [:]
    def offset = state.energyOffset[channel] ?: 0.0
    if (offset > rawValue) { state.energyOffset[channel] = 0.0; offset = 0.0 }
    return Math.max(0.0, rawValue - offset)
}

void ensureChildExists(String channel) {
    String childDni = "${device.deviceNetworkId}-CT${channel}"
    def child = getChildDevice(childDni)
    if (!child) {
        child = addChildDevice("hubitat", "Generic Component Power Meter", childDni, [
            name: "Channel ${channel}",
            label: "${device.label ?: device.name} CT${channel}",
            isComponent: false
        ])
        try { if(device.roomName) child.setRoom(device.roomName) } catch(e){}
        sendEvent(name: "channelCount", value: getChildDevices().size())
    }
}

void processTemperature(def rawVal) {
    def val = rawVal.toDouble()
    String unit = "°F"
    
    // Device reports Celsius
    if (tempScale == "Celsius") { 
        unit = "°C"
    } else if (tempScale == "Kelvin") { 
        val = val + 273.15
        unit = "K"
    } else {
        // Default Fahrenheit
        val = (val * 1.8) + 32
    }
    
    updateSensor("temperature", val, unit, 0.1)
}

boolean isRateLimited(String key) {
    if (!state.lastUpdate) state.lastUpdate = [:]
    def now = now()
    def last = state.lastUpdate[key] ?: 0
    def hysteresisMs = (sensorHysteresis != null) ? (sensorHysteresis * 1000) : 5000
    if (now - last < hysteresisMs) return true
    state.lastUpdate[key] = now
    return false
}

def clamp(def value) {
    if (value instanceof Number) return Math.round(value * 100) / 100
    return value
}

void checkAndSend(def dev, String name, def val, String unit, def threshold) {
    def currentVal = dev.currentValue(name) ?: 0
    if (Math.abs(val - currentVal) >= (threshold ?: 0)) {
        dev.sendEvent(name: name, value: clamp(val), unit: unit)
    }
}

void updateSensor(String name, def value, String unit, def threshold, boolean skipRateLimit = false) {
    if (!skipRateLimit && isRateLimited("Indep_${name}")) return
    def currentVal = device.currentValue(name) ?: 0
    if (Math.abs(value - currentVal) >= (threshold ?: 0)) {
        sendEvent(name: name, value: clamp(value), unit: unit)
    }
}

void forceRenaming() { renameChildren() }

void renameChildren() {
    if (!channelNames) return
    def names = channelNames.split(",").collect{ it.trim() }
    getChildDevices().each { child ->
        String dni = child.deviceNetworkId
        String channelStr = dni.tokenize("CT").last()
        if (channelStr.isNumber()) {
            int idx = channelStr.toInteger()
            if (idx <= names.size()) {
                String newName = names[idx-1]
                if (newName && child.label != newName) child.setLabel(newName)
            }
        }
    }
}

void syncRooms() {
    try {
        String parentRoom = device.roomName
        if (parentRoom) getChildDevices().each { try { it.setRoom(parentRoom) } catch(e){} }
    } catch(e){}
}

void refresh() { initialize() }
void poll() { watchdog() }
void componentRefresh(com.hubitat.app.DeviceWrapper child) { }
void recreateChildren() { log.info "Children are auto-discovered." }
