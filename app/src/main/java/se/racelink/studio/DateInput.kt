package se.racelink.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import java.util.Locale
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal fun dateMillis(value: String): Long? = runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
internal fun dateFromMillis(value: Long): String = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().toString()

@OptIn(ExperimentalMaterial3Api::class)
private val SwedishDatePickerSaver = Saver<DatePickerState, ArrayList<Long>>(
    save = { arrayListOf(it.selectedDateMillis ?: Long.MIN_VALUE, it.displayedMonthMillis) },
    restore = { DatePickerState(locale = Locale.forLanguageTag("sv-SE"), initialSelectedDateMillis = it[0].takeUnless { date -> date == Long.MIN_VALUE }, initialDisplayedMonthMillis = it[1]) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DateInput(value: String, change: (String) -> Unit, label: String, enabled: Boolean = true) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    val valid = dateMillis(value) != null
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = !valid, supportingText = { Text(if (valid) "ÅÅÅÅ-MM-DD" else "Ange ett giltigt datum som ÅÅÅÅ-MM-DD") },
        trailingIcon = { TextButton(enabled = enabled, onClick = { calendar = true }) { Text("Kalender") } }, modifier = Modifier.fillMaxWidth())
    if (calendar) {
        val context = LocalContext.current
        val swedish = remember(context) {
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("sv-SE")) }
            context.createConfigurationContext(configuration)
        }
        val picker = rememberSaveable(saver = SwedishDatePickerSaver) {
            DatePickerState(locale = Locale.forLanguageTag("sv-SE"), initialSelectedDateMillis = dateMillis(value)?.takeIf { LocalDate.parse(value).year in 1900..2100 })
        }
        DatePickerDialog(onDismissRequest = { calendar = false },
            confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { change(dateFromMillis(it)) }; calendar = false
            }) { Text("Välj datum") } }, dismissButton = { TextButton(onClick = { calendar = false }) { Text("Avbryt") } }) {
            CompositionLocalProvider(LocalContext provides swedish, LocalConfiguration provides swedish.resources.configuration) {
                DatePicker(picker, title = { Text("Välj datum", Modifier.padding(16.dp)) })
            }
        }
    }
}
