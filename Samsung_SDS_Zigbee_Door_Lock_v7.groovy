/**
 *  Samsung SDS Zigbee Door Lock Driver for Hubitat (v7)
 *  Supports: SHP-960 Plus, SHP-DP960SG (DADT302 Zigbee module)
 *  Manufacturer: SAMSUNG SDS
 *
 *  v7 changes:
 *   - Fixed a state-mismatch bug: 오빠 noticed the lock sometimes showed
 *     "unlocked" in Hubitat right after Configure/Refresh even though the
 *     door was physically locked. Logs showed the on-demand LockState
 *     attribute read (cluster 0x0101 attr 0x0000, via readAttribute) can
 *     return a stale/incorrect value, while the push-based Operation Event
 *     Notification (command 0x20, sent autonomously by the lock on every
 *     real lock/unlock/autolock action) was 100% accurate in every log
 *     reviewed. So: readAttribute(0x0101, 0x0000) is no longer used to
 *     update the "lock" attribute. refresh() still reads it for logging/
 *     debugging purposes only, but parseReadAttr() no longer creates a
 *     lock/contact event from that response - only the autonomous
 *     Operation Event path (parseOperationEvent) updates lock/contact now.
 *
 *  v6 changes:
 *   - Added explicit ZDO binding requests ("zdo bind ...") in configure()
 *     for Power Config (0x0001) and Door Lock (0x0101) clusters. After 24+
 *     hours the battery voltage attribute still autonomously reported 0,
 *     despite Samsung's own DTH comments claiming ~12h self-reporting.
 *     configureReporting() alone sets thresholds but doesn't guarantee a
 *     binding table entry telling the device WHERE to send reports; this
 *     adds that missing piece as a troubleshooting step. Not guaranteed to
 *     fix it - possible the unit's voltage sensing itself just isn't wired
 *     up in this firmware - but worth trying before giving up on it.
 *
 *  v5 changes - aligned against Samsung SDS's own official SmartThings DTH
 *  source (SmartThingsCommunity/SmartThingsPublic, devicetypes/samsungsds/
 *  samsung-smart-doorlock.src, Apache-2.0, 2018), which confirms everything
 *  found by experimentation in v3/v4 was correct, and fixes the battery
 *  voltage->percent formula:
 *   - Confirmed: unlock = cluster 0x0101, command 0x1F, mfgCode 0x0003.
 *     Official code hardcodes payload "100431323335" (= prefix 0x10, length
 *     4, ASCII "1235" - an apparent factory/master code). Our own empty-code
 *     payload (오빠's confirmed working test) is equally valid and simpler,
 *     so unlock() is left as-is from v3/v4.
 *   - Battery: official formula is volts = raw/10 (attr 0x0001/0x0020),
 *     minVolts=4.0, maxVolts=6.0, pct = (volts-4.0)/(6.0-4.0)*100, clamped
 *     to 100. (>6.5V is treated as a bad/out-of-range reading.) This is a
 *     4-cell-AA (6V) assumption baked into Samsung's own code, not the
 *     cell-count-based guess used in v4 - replaced accordingly.
 *   - Per official comments, the lock does NOT reliably answer on-demand
 *     reads for lock/battery state - it pushes them autonomously on its own
 *     schedule (lock state ~every 30 min, battery ~every 12 hours, or right
 *     after a battery swap). So a "battery still 0" reading after Configure/
 *     Refresh is expected and not a bug - it should update within 12 hours
 *     on its own. refresh() still issues the read as a best-effort nudge.
 *   - Operation Event source/code mapping expanded to match the official
 *     table exactly: sources 0-5 (keypad/command/manual/rfid/fingerprint/
 *     bluetooth), codes 7/8/13->locked, 9/14->unlocked, 10->autolock.
 *   - Added a one-time model-name read in configure() (cluster 0x0000,
 *     command 0x1E, mfgCode 0x0003) purely for a nicer device label; safe
 *     read-only manufacturer-specific command per the official source.
 *
 *  v3 changes (carried over): manufacturer-specific virtual-keypad unlock.
 *  v2 changes (carried over): IAS Zone enrollResponse, zone-status parse
 *  fix, setCode() UserStatus/UserType fix.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
    definition(name: "Samsung SDS Zigbee Door Lock v7", namespace: "community", author: "community") {
        capability "Lock"
        capability "LockCodes"
        capability "Battery"
        capability "ContactSensor"
        capability "TamperAlert"
        capability "Refresh"
        capability "Configuration"
        capability "Actuator"
        capability "Sensor"
        capability "HealthCheck"

        attribute "lastCodeName", "string"
        attribute "lastMethod", "string"
        attribute "batteryVoltage", "number"
        attribute "modelName", "string"

        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0004,0005,0009,0101", outClusters: "0019", manufacturer: "SAMSUNG SDS", deviceJoinName: "Samsung SDS Door Lock"
        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0020,0101,0500", outClusters: "000A,0019", manufacturer: "SAMSUNG SDS", model: "SHP-DP960SG", deviceJoinName: "Samsung SDS Door Lock"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true
        input name: "maxPinLength", type: "number", title: "Max PIN length", defaultValue: 4, range: "4..8"
        input name: "unlockCode", type: "password", title: "Unlock virtual-keypad code (leave blank - confirmed empty code works)", required: false
    }
}

def installed() {
    log.info "${device.displayName} installed"
    initialize()
}

def updated() {
    log.info "${device.displayName} updated"
    initialize()
}

def initialize() {
    if (logEnable) log.debug "${device.displayName} initializing"
    sendEvent(name: "checkInterval", value: 7200, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.warn "${device.displayName} debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def configure() {
    if (logEnable) log.debug "${device.displayName} configuring..."
    def cmds = []
    // IAS Zone enrollment response - required for tamper/contact events to be delivered
    cmds += zigbee.enrollResponse()
    // Explicit ZDO binding - tell the device to send autonomous reports for
    // Power Config (battery, 0x0001) and Door Lock (0x0101) clusters to this
    // hub. configureReporting() alone sets the reporting thresholds, but does
    // not guarantee a binding table entry exists telling the device WHERE to
    // send those reports. Added as a v6 troubleshooting step for the battery
    // voltage attribute that has stayed at 0 for 24+ hours despite Samsung's
    // own DTH comments claiming autonomous ~12h battery reports.
    cmds << "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}"
    cmds << "delay 200"
    cmds << "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0101 {${device.zigbeeId}} {}"
    cmds << "delay 200"
    // Re-send reporting configuration after binding, in case ordering matters
    cmds += zigbee.configureReporting(0x0001, 0x0020, 0x20, 30, 3600, 1)
    cmds += zigbee.configureReporting(0x0101, 0x0000, 0x30, 0, 3600, null)
    // One-time model name read (manufacturer-specific, read-only) - cosmetic only
    cmds += zigbee.command(0x0000, 0x1E, [mfgCode: "0x0003"], "")
    // Best-effort battery voltage / lock state read. Per Samsung's own DTH,
    // this device mainly self-reports on its own schedule (~30min lock,
    // ~12h battery), so don't be surprised if this doesn't immediately answer.
    cmds += refresh()
    return cmds
}

def refresh() {
    if (logEnable) log.debug "${device.displayName} refreshing"
    def cmds = []
    cmds += zigbee.readAttribute(0x0001, 0x0020)  // battery voltage (best-effort)
    cmds += zigbee.readAttribute(0x0101, 0x0000)  // lock state
    return cmds
}

def lock() {
    if (logEnable) log.debug "${device.displayName} locking"
    return zigbee.command(0x0101, 0x00)
}

// Manufacturer-specific "virtual keypad entry" unlock.
// Cluster 0x0101, command 0x1F, manufacturerCode 0x0003 (Samsung SDS).
// Confirmed against Samsung's own official DTH source. Payload format:
// [0x10, codeLength, ...asciiDigitBytes]. Empty code (length 0) confirmed
// working; Samsung's own code hardcodes "1235" as an apparent master code -
// either works, empty is simpler and doesn't require storing a PIN at all.
def unlock() {
    if (logEnable) log.debug "${device.displayName} unlocking (manufacturer-specific virtual keypad command)"
    def code = (unlockCode ?: "").toString()
    def codeHex = code ? code.bytes.collect { zigbee.convertToHexString(it, 2) }.join("") : ""
    def lengthHex = zigbee.convertToHexString(code.length(), 2)
    def payload = "10${lengthHex}${codeHex}"
    return zigbee.command(0x0101, 0x1F, [mfgCode: "0x0003"], payload)
}

def setCode(codeSlot, codePIN, codeName = null) {
    if (codeSlot == null || codePIN == null) {
        log.error "setCode: codeSlot and codePIN are required"
        return
    }
    def slot = codeSlot as Integer
    def pin = codePIN.toString()
    def name = codeName ?: "Code ${slot}"
    def slotHex = zigbee.convertToHexString(slot, 2)
    def pinHex = pin.bytes.collect { zigbee.convertToHexString(it, 2) }.join("")
    def pinLen = zigbee.convertToHexString(pin.length(), 2)
    def payload = "${slotHex}0100${pinLen}${pinHex}"
    def lockCodes = getLockCodes()
    lockCodes[slot.toString()] = name
    saveLockCodes(lockCodes)
    updateCodeChanged(slot, name, "added")
    return zigbee.command(0x0101, 0x05, payload)
}

def deleteCode(codeSlot) {
    def slot = codeSlot as Integer
    def slotHex = zigbee.convertToHexString(slot, 2)
    def lockCodes = getLockCodes()
    def codeName = lockCodes[slot.toString()] ?: "Code ${slot}"
    lockCodes.remove(slot.toString())
    saveLockCodes(lockCodes)
    updateCodeChanged(slot, codeName, "deleted")
    return zigbee.command(0x0101, 0x07, slotHex)
}

def nameSlot(codeSlot, codeName) {
    def slot = codeSlot as Integer
    def lockCodes = getLockCodes()
    lockCodes[slot.toString()] = codeName
    saveLockCodes(lockCodes)
    if (txtEnable) log.info "${device.displayName} renamed slot ${slot} to ${codeName}"
}

def getCodes() {
    return zigbee.readAttribute(0x0101, 0x0010)
}

def reloadAllCodes() {
    return refresh()
}

def setCodeLength(length) {
    device.updateSetting("maxPinLength", [value: length, type: "number"])
    if (txtEnable) log.info "${device.displayName} code length set to ${length}"
}

def parse(String description) {
    if (logEnable) log.debug "${device.displayName} parse: ${description}"
    def event = null
    if (description?.startsWith("read attr -")) {
        event = parseReadAttr(description)
    } else if (description?.startsWith("catchall:")) {
        event = parseCatchAll(description)
    } else if (description?.startsWith("zone status")) {
        event = parseZoneStatus(description)
    }
    if (event) {
        if (event instanceof List) {
            event.each { sendEvent(it) }
        } else {
            sendEvent(event)
        }
    }
}

def parseReadAttr(String description) {
    def descMap = zigbee.parseDescriptionAsMap(description)
    def cluster = descMap.cluster ? Integer.parseInt(descMap.cluster, 16) : null
    def attrId  = descMap.attrId  ? Integer.parseInt(descMap.attrId, 16)  : null
    def value   = descMap.value
    // Battery voltage: cluster 0x0001, attr 0x0020
    if (cluster == 1 && attrId == 32) {
        return parseBatteryVoltage(value)
    }
    // Battery percentage (kept as fallback in case a unit supports it)
    if (cluster == 1 && attrId == 33) {
        return parseBatteryPercent(value)
    }
    // Door Lock cluster 0x0101, attr 0x0000 (lock state) - v7: no longer used
    // to update the lock/contact attributes. On-demand reads of this
    // attribute were found to sometimes return a stale/incorrect value
    // (오빠's "locked door showing unlocked" report). Logged only; the
    // authoritative source is the autonomous Operation Event Notification
    // handled in parseOperationEvent().
    if (cluster == 257 && attrId == 0) {
        logLockStateReadOnly(value)
        return null
    }
    return null
}

def parseCatchAll(String description) {
    def descMap = zigbee.parseDescriptionAsMap(description)
    def cluster = descMap.clusterId ? Integer.parseInt(descMap.clusterId, 16) : null
    def command = descMap.command   ? Integer.parseInt(descMap.command, 16)   : null
    def data    = descMap.data
    if (cluster == 257) {  // 0x0101 Door Lock
        if (command == 32) {  // 0x20 Operation Event Notification
            return parseOperationEvent(data)
        }
        if (command == 0 || command == 1 || command == 31) {
            runInMillis(500, "refresh")
        }
    } else if (cluster == 0 && command == 30) {  // 0x1E model name read response
        return parseModelName(data)
    }
    return null
}

def parseModelName(List data) {
    if (!data || data.size() < 2) return null
    try {
        int length = Integer.parseInt(data[0], 16)
        def sb = new StringBuilder()
        for (int i = 1; i < data.size() && i <= length; i++) {
            int c = Integer.parseInt(data[i], 16)
            if (c == 32) break
            sb.append((char) c)
        }
        def model = sb.toString()
        if (model) {
            if (txtEnable) log.info "${device.displayName} model name: ${model}"
            return createEvent(name: "modelName", value: model, displayed: false)
        }
    } catch (e) {
        if (logEnable) log.debug "${device.displayName} failed to parse model name: ${e}"
    }
    return null
}

def parseZoneStatus(String description) {
    def rawToken = description.split()[2].replace("0x", "")
    def zoneStatus = Integer.parseInt(rawToken, 16)
    def tamper = (zoneStatus & 0x04) ? "detected" : "clear"
    def contact = (zoneStatus & 0x01) ? "open" : "closed"
    def events = []
    events << createEvent(name: "tamper", value: tamper)
    events << createEvent(name: "contact", value: contact)
    return events
}

// Battery voltage->percent formula per Samsung SDS's own official
// SmartThings DTH: raw ZCL 0.1V units, minVolts=4.0, maxVolts=6.0
// (i.e. a 4x AA / 6V pack assumption baked into their own firmware-facing code).
def parseBatteryVoltage(String value) {
    if (!value) return null
    def raw = Integer.parseInt(value, 16)
    def volts = raw / 10.0
    def events = []
    events << createEvent(name: "batteryVoltage", value: volts, unit: "V", descriptionText: "${device.displayName} battery voltage is ${volts}V")
    if (volts > 6.5) {
        if (txtEnable) log.info "${device.displayName} battery voltage ${volts}V is out of expected range - treating as unreliable reading"
        return events
    }
    def minVolts = 4.0
    def maxVolts = 6.0
    def pct = Math.round(((volts - minVolts) / (maxVolts - minVolts)) * 100)
    if (pct > 100) pct = 100
    if (pct < 0) pct = 0
    if (txtEnable) log.info "${device.displayName} battery voltage ${volts}V (~${pct}%)"
    events << createEvent(name: "battery", value: pct, unit: "%", descriptionText: "${device.displayName} battery is ${pct}% (estimated from voltage)")
    return events
}

// Fallback path if a unit actually supports the percentage attribute directly.
def parseBatteryPercent(String value) {
    if (!value) return null
    def rawVal = Integer.parseInt(value, 16)
    def pct = Math.round(rawVal / 2)
    if (pct > 100) pct = 100
    if (txtEnable) log.info "${device.displayName} battery ${pct}%"
    return createEvent(name: "battery", value: pct, unit: "%", descriptionText: "${device.displayName} battery is ${pct}%")
}

// v7: no longer creates lock/contact events - on-demand reads of this
// attribute were found to be occasionally unreliable. Kept as a debug-only
// log so 오빠 can still see what the device answers when queried, without
// it overriding the authoritative state from parseOperationEvent().
def logLockStateReadOnly(String value) {
    if (!value) return
    def stateVal = Integer.parseInt(value, 16)
    def lockStateMap = [0: "not fully locked", 1: "locked", 2: "unlocked"]
    def lockState = lockStateMap[stateVal] ?: "unknown"
    if (logEnable) log.debug "${device.displayName} on-demand read says lock is ${lockState} (not used to update state - informational only)"
}

// Operation Event source/code mapping - matches Samsung SDS's own official
// SmartThings DTH exactly (sources 0-5, codes 1/2/7/8/9/10/13/14).
def parseOperationEvent(List data) {
    if (!data || data.size() < 2) return null
    def methodMap = [0: "keypad", 1: "command", 2: "manual", 3: "rfid", 4: "fingerprint", 5: "bluetooth"]
    def operationEventSource = Integer.parseInt(data[0], 16)
    def operationEventCode   = Integer.parseInt(data[1], 16)
    def userId = null
    if (data.size() >= 4) {
        try { userId = Integer.parseInt(data[3] + data[2], 16) } catch (e) { userId = null }
    }
    def method = methodMap[operationEventSource] ?: "manual"
    def lockCodes = getLockCodes()
    def codeName = (userId != null && userId > 0 && userId < 0xFFFF) ? (lockCodes[userId.toString()] ?: "슬롯 ${userId}") : ""

    def lockState = null
    def isAuto = false
    switch (operationEventCode) {
        case 1: case 7: case 8: case 13:
            lockState = "locked"; break
        case 2: case 9: case 14:
            lockState = "unlocked"; break
        case 10:
            lockState = "locked"; isAuto = true; break
        default:
            // 3/4/5/6 are failure events (invalid PIN/schedule) - no state change
            return null
    }

    def eventMethod = isAuto ? "autolock" : method
    def events = []
    def dataMap = [method: eventMethod]
    if (codeName) {
        dataMap.codeId = userId.toString()
        dataMap.codeName = codeName
    }
    events << createEvent(
        name: "lock", value: lockState, data: dataMap,
        descriptionText: "${device.displayName} is ${lockState} [${eventMethod}${codeName ? ' - ' + codeName : ''}]",
        isStateChange: true
    )
    if (lockState == "unlocked") {
        events << createEvent(name: "contact", value: "open")
        if (codeName) sendEvent(name: "lastCodeName", value: codeName)
    } else {
        events << createEvent(name: "contact", value: "closed")
    }
    sendEvent(name: "lastMethod", value: eventMethod)
    if (txtEnable) log.info "${device.displayName} ${lockState} by ${eventMethod}${codeName ? ' (' + codeName + ')' : ''}"
    return events
}

def getLockCodes() {
    def lockCodesJson = device.currentValue("lockCodes")
    if (!lockCodesJson) return [:]
    try {
        return new JsonSlurper().parseText(lockCodesJson) as Map
    } catch (e) {
        return [:]
    }
}

def saveLockCodes(Map codes) {
    sendEvent(name: "lockCodes", value: JsonOutput.toJson(codes), displayed: false)
}

def updateCodeChanged(int slot, String codeName, String action) {
    sendEvent(name: "codeChanged", value: "${slot} ${action}", data: [codeName: codeName], displayed: false)
}
