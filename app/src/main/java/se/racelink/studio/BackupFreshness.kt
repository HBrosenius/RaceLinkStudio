package se.racelink.studio

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class BackupReceipt(val time: String, val fingerprint: String)

internal fun displayStoredTime(time: String): String = runCatching {
    java.time.Instant.parse(time).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.forLanguageTag("sv-SE")))
}.getOrDefault(time)

private fun canonicalJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonicalJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonicalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

internal fun Training.backupFingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest(canonicalJson(TrainingJson.encode(this)).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun backupFreshness(receipt: BackupReceipt?, fingerprint: String?): String = when {
    receipt == null -> "Ingen extern säkerhetskopia har registrerats på denna enhet."
    fingerprint == null -> "Senaste externa säkerhetskopia: ${displayStoredTime(receipt.time)}. Aktuell status kan inte kontrolleras."
    receipt.fingerprint == fingerprint -> "Senaste externa säkerhetskopia: ${displayStoredTime(receipt.time)}. Alla aktuella uppgifter finns i kopian."
    else -> "Senaste externa säkerhetskopia: ${displayStoredTime(receipt.time)}. Uppgifterna har ändrats sedan kopian sparades. Spara en ny kopia."
}
