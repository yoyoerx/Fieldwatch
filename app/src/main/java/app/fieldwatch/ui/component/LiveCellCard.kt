package app.fieldwatch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fieldwatch.domain.CellLive
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.ui.FieldwatchViewModel

/** The connected cell and the cells detected nearby. The last item on every Live list. */
@Composable
fun LiveCellCard(vm: FieldwatchViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val reading by vm.cellReading.collectAsStateWithLifecycle()
    val verdict by vm.cellVerdict.collectAsStateWithLifecycle()
    val view = CellLive.view(reading, verdict, ui.settings.cellWatch, ui.settings.demoMode)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                LanguageText.text("live.cell.title"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val serving = view.serving
            if (serving != null) {
                Text(
                    serving.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (serving.alarming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                serving.lines.forEach { Small(it) }
                serving.reasons.forEach { reason ->
                    Text(
                        "• $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            view.note?.let { Small(it) }
            if (view.neighbors.isNotEmpty()) {
                HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                )
                Text(
                    LanguageText.text(
                        "live.cell.detected",
                        mapOf("n" to (view.neighbors.size + view.more).toString()),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                view.neighbors.forEach { Small(it.text) }
                if (view.more > 0) Small(LanguageText.text("live.cell.more", mapOf("n" to view.more.toString())))
            }
        }
    }
}

@Composable
private fun Small(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
