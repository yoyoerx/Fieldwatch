package app.fieldwatch.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.domain.PlaceMemory
import app.fieldwatch.domain.PlaceReport
import app.fieldwatch.ui.component.FieldwatchActionButton
import app.fieldwatch.ui.component.FieldwatchFilterChip
import app.fieldwatch.ui.FieldwatchUi
import app.fieldwatch.ui.FieldwatchViewModel
import java.time.ZoneId

/**
 * Radios heard at two or more places, today or in the last 7 days.
 * The map is where this phone was. Each radio keeps one color on the map and in the list.
 * Rows are recomputed every 30 seconds, not on every scan.
 */
@Composable
internal fun PlaceReportPage(
    state: FieldwatchUi,
    vm: FieldwatchViewModel,
    onOpen: (String) -> Unit,
) {
    val settings = state.settings
    if (!settings.placeWatch) {
        Note(LanguageText.text("reports.places.off"))
        return
    }
    val streets by vm.placeStreetNames.collectAsStateWithLifecycle()
    val mapKeyRev by vm.mapKeyRevision.collectAsStateWithLifecycle()
    LaunchedEffect(settings.onlineLookup, settings.demoMode) {
        vm.refreshPlaceReportStreets()
    }
    var today by rememberSaveable { mutableStateOf(false) }
    var showFamiliar by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var askMine by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val zone = remember { ZoneId.systemDefault() }
    val shownStreets = if (settings.demoMode || !settings.onlineLookup) emptyMap() else streets
    val (rows, summary) = remember(tick, today, showFamiliar, state.watchlist, state.fleets, settings.demoMode, shownStreets) {
        val now = System.currentTimeMillis()
        val since = if (today) PlaceReport.todayStart(now, zone) else now - PlaceMemory.WINDOW_MS
        val records = vm.placeRecords()
        val rows = PlaceReport.rows(
            records = records,
            devices = state.devices,
            fleets = state.fleets,
            watchlist = state.watchlist,
            now = now,
            zone = zone,
            demo = settings.demoMode,
            streets = shownStreets,
            since = since,
            showFamiliar = showFamiliar,
        )
        rows to PlaceReport.summary(rows, records, state.watchlist, since, now, zone)
    }
    val chains = remember(tick, settings.placeWide) { vm.rotationChains() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldwatchFilterChip(
                selected = today,
                onClick = { today = true },
                label = { Text(LanguageText.text("reports.places.today")) },
            )
            FieldwatchFilterChip(
                selected = !today,
                onClick = { today = false },
                label = { Text(LanguageText.text("reports.places.week")) },
            )
            if (summary.familiar > 0 || showFamiliar) {
                FieldwatchFilterChip(
                    selected = showFamiliar,
                    onClick = { showFamiliar = !showFamiliar },
                    label = {
                        Text(LanguageText.text("reports.places.known.show", mapOf("n" to summary.familiar.toString())))
                    },
                )
            }
        }
        Note(LanguageText.text(if (today) "reports.places.today.blurb" else "reports.places.blurb"))
        Text(
            LanguageText.text(
                "reports.places.summary",
                mapOf("n" to summary.radios.toString(), "stops" to summary.stops.toString()),
            ),
            style = MaterialTheme.typography.labelLarge,
        )
        if (summary.routine > 0) {
            Note(LanguageText.text("reports.places.routine", mapOf("n" to summary.routine.toString())))
        }
        val likely = rows.filter { it.likelyMine }
        if (likely.isNotEmpty()) {
            Note(LanguageText.text("reports.places.mine.note"))
            FieldwatchActionButton(
                onClick = { askMine = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(LanguageText.text("reports.places.mine.all", mapOf("n" to likely.size.toString()))) }
        }
        if (askMine) {
            AlertDialog(
                onDismissRequest = { askMine = false },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(LanguageText.text("reports.places.mine.ask", mapOf("n" to likely.size.toString())))
                        likely.forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        askMine = false
                        vm.markPlaceRadiosMine(likely.map { it.deviceKey })
                    }) { Text(LanguageText.text("reports.places.mine")) }
                },
                dismissButton = {
                    TextButton(onClick = { askMine = false }) { Text(LanguageText.text("language.cancel")) }
                },
            )
        }
        if (settings.demoMode) {
            Text(
                LanguageText.text("reports.places.private"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (rows.isEmpty()) {
            Note(LanguageText.text("reports.places.empty"))
            RotationSection(chains, vm)
            return@Column
        }
        val focus = PlaceReport.newest(rows)
        val pins = PlaceReport.pins(rows)
        val mapKey = remember(settings.mapBasemap, mapKeyRev) { vm.mapKey(settings.mapBasemap) }
        if (focus != null && pins.isNotEmpty() && settings.onlineLookup && !settings.demoMode) {
            PlaceStopsMap(
                pins = pins,
                focusLat = focus.lat,
                focusLon = focus.lon,
                basemap = settings.mapBasemap,
                mapKey = mapKey,
                overlays = settings.mapOverlays,
            )
        }
        rows.forEach { row ->
            PlaceReportRow(
                row,
                onOpen = { onOpen(row.deviceKey) },
                onMine = { vm.markPlaceRadiosMine(listOf(row.deviceKey)) },
                onFamiliar = { vm.markPlaceRadiosFamiliar(listOf(row.deviceKey), !row.familiar) },
            )
        }
        RotationSection(chains, vm)
    }
}

@Composable
private fun PlaceReportRow(
    row: PlaceReport.Row,
    onOpen: () -> Unit,
    onMine: () -> Unit,
    onFamiliar: () -> Unit,
) {
    val color = Color(row.color)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(12.dp)
                    .background(color, CircleShape),
            )
            Column(
                Modifier
                    .padding(start = 10.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        LanguageText.text("reports.places.count", mapOf("n" to row.count.toString())),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tag(LanguageText.text(kindKey(row.kind)), MaterialTheme.colorScheme.secondaryContainer)
                    if (row.likelyMine) {
                        Tag(LanguageText.text("reports.places.likely"), MaterialTheme.colorScheme.tertiaryContainer)
                    }
                    if (row.familiar) {
                        Tag(LanguageText.text("reports.places.known.tag"), MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                Text(
                    metrics(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.address.isNotEmpty()) {
                    Text(
                        row.address,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (row.visits.isNotEmpty()) {
                    PlaceVisits(row.visits)
                }
                if (row.note.isNotEmpty()) {
                    Text(
                        row.note,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.padding(top = 2.dp)) {
                    TextButton(onClick = onMine) {
                        Text(LanguageText.text("reports.places.mine"))
                    }
                    TextButton(onClick = onFamiliar) {
                        Text(LanguageText.text(if (row.familiar) "reports.places.known.clear" else "place.known"))
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Experimental chains that followed one radio across address changes. */
@Composable
private fun RotationSection(chains: List<app.fieldwatch.domain.RotationLinker.Chain>, vm: FieldwatchViewModel) {
    var hiddenNow by remember { mutableStateOf(emptySet<Long>()) }
    val shown = chains.filter { it.id !in hiddenNow }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            LanguageText.text("reports.rotation.title"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Note(LanguageText.text("reports.rotation.note"))
        if (shown.isEmpty()) {
            Note(LanguageText.text("reports.rotation.empty"))
            return@Column
        }
        val clock = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US) }
        shown.forEach { chain ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(chain.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            LanguageText.text(
                                "reports.rotation.line",
                                mapOf(
                                    "n" to chain.links.toString(),
                                    "min" to ((chain.lastAt - chain.firstAt) / 60_000L).toString(),
                                    "km" to "%.1f".format(java.util.Locale.US, chain.withYouM / 1000.0),
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${clock.format(java.util.Date(chain.firstAt))} – ${clock.format(java.util.Date(chain.lastAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        vm.hideRotationChain(chain.id)
                        hiddenNow = hiddenNow + chain.id
                    }) { Text(LanguageText.text("reports.rotation.hide")) }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = color) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun kindKey(kind: PlaceReport.Kind): String = when (kind) {
    PlaceReport.Kind.TAG -> "reports.places.kind.tag"
    PlaceReport.Kind.HOTSPOT -> "reports.places.kind.hotspot"
    PlaceReport.Kind.BLE -> "reports.places.kind.ble"
    PlaceReport.Kind.DRONE -> "reports.places.kind.drone"
}

/** Stops, days, average signal, and score on one line. */
private fun metrics(row: PlaceReport.Row): String = buildList {
    add(LanguageText.text("reports.places.count", mapOf("n" to row.count.toString())))
    if (row.oneOff > 0) add(LanguageText.text("reports.places.oneoff", mapOf("n" to row.oneOff.toString())))
    add(
        if (row.days == 1) LanguageText.text("reports.places.days.one")
        else LanguageText.text("reports.places.days", mapOf("n" to row.days.toString())),
    )
    row.avgRssi?.let { add(LanguageText.text("reports.places.avg", mapOf("dbm" to it.toString()))) }
    add(LanguageText.text("reports.places.score", mapOf("n" to row.score.toString())))
}.joinToString(" · ")

@Composable
private fun PlaceVisits(visits: List<PlaceReport.Visit>) {
    Column(Modifier.padding(top = 8.dp)) {
        visits.forEachIndexed { index, visit ->
            if (index > 0) {
                HorizontalDivider(
                    Modifier.padding(start = 28.dp, top = 7.dp, bottom = 7.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                )
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    visit.number.toString(),
                    modifier = Modifier.width(28.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        visit.time,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val where = visit.street.ifBlank { visit.name }
                    if (where.isNotEmpty()) {
                        Text(
                            where,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (visit.street.isNotEmpty() && visit.name.isNotEmpty()) {
                        Text(
                            visit.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (visit.coord.isNotEmpty()) {
                        Text(
                            visit.coord,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
