package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellLiveTest {
    @Test
    fun theConnectedCellShowsOperatorBandIdsSignalAndDistance() {
        val view = CellLive.view(reading(), CellVerdict(), watchOn = true, demo = false)
        val serving = assertNotNullServing(view)
        assertTrue(serving.title, serving.title.contains("4G"))
        assertTrue(serving.title, serving.title.contains("T-Mobile"))
        val text = serving.lines.joinToString("\n")
        assertTrue(text, text.contains("310-260"))
        assertTrue(text, text.contains("12"))
        assertTrue(text, text.contains("4321"))
        assertTrue(text, text.contains("98765"))
        assertTrue(text, text.contains("-115 dBm"))
        assertTrue(text, text.contains("about 7.0 km"))
        assertTrue(text, text.contains("${LanguageText.text("cell.score")} 0"))
        assertNull(view.note)
    }

    @Test
    fun privacyModeMasksTheAreaAndTheCellId() {
        val text = assertNotNullServing(CellLive.view(reading(), CellVerdict(), true, demo = true)).lines.joinToString("\n")
        assertFalse(text, text.contains("4321"))
        assertFalse(text, text.contains("98765"))
        assertTrue(text, text.contains("masked"))
    }

    @Test
    fun reasonsShowWhenTheScoreHasThem() {
        val verdict = CellVerdict(listOf(CellFlag.TEST_NETWORK), 100)
        val serving = assertNotNullServing(CellLive.view(reading(), verdict, true, false))
        assertTrue(serving.alarming)
        assertEquals(listOf(LanguageText.text("cell.flag.test.network")), serving.reasons)
        // With the watch off there is no score to show.
        val off = assertNotNullServing(CellLive.view(reading(), verdict, false, false))
        assertFalse(off.alarming)
        assertTrue(off.reasons.isEmpty())
        assertTrue(off.lines.contains(LanguageText.text("live.cell.watch.off")))
    }

    @Test
    fun neighborsAreStrongestFirstFoldedAndCapped() {
        val many = (0 until 12).map { CellNeighbor(CellGen.LTE, pci = it, channel = 5110, signal = -120 + it) } +
            CellNeighbor(CellGen.LTE, pci = 11, channel = 5110, signal = -109)
        val view = CellLive.view(reading(neighbors = many), CellVerdict(), true, false)
        assertEquals(CellLive.NEIGHBOR_CAP, view.neighbors.size)
        assertEquals(12 - CellLive.NEIGHBOR_CAP, view.more)
        assertTrue(view.neighbors.first().text, view.neighbors.first().text.contains("-109 dBm"))
        assertTrue(view.neighbors.first().text, view.neighbors.first().text.contains("${LanguageText.text("cell.pci")} 11"))
    }

    @Test
    fun noConnectedCellLeavesANoteAndStillListsNeighbors() {
        val view = CellLive.view(
            CellReading(neighbors = listOf(CellNeighbor(CellGen.NR, pci = 300, signal = -100))),
            CellVerdict(),
            true,
            false,
        )
        assertNull(view.serving)
        assertEquals(LanguageText.text("live.cell.none"), view.note)
        assertEquals(1, view.neighbors.size)
        assertTrue(view.neighbors.single().text.startsWith("5G"))
    }

    @Test
    fun aPartialIdentityNamesWhatWasNotReturned() {
        val partial = CellReading(registered = true, generation = CellGen.LTE, emptyFields = listOf(CellField.CELL))
        val text = assertNotNullServing(CellLive.view(partial, CellVerdict(), true, false)).lines.joinToString("\n")
        assertTrue(text, text.contains(LanguageText.text("cell.cell")))
    }

    @Test
    fun timingAdvanceBecomesADistancePerGeneration() {
        assertEquals(6.95, CellLive.towerKm(CellGen.LTE, 89)!!, 0.01)
        assertEquals(1.107, CellLive.towerKm(CellGen.GSM, 2)!!, 0.001)
        assertEquals(1.499, CellLive.towerKm(CellGen.NR, 10)!!, 0.001)
        assertNull(CellLive.towerKm(CellGen.WCDMA, 5))
        assertNull(CellLive.towerKm(CellGen.LTE, -1))
    }

    private fun assertNotNullServing(view: CellLive.View): CellLive.Serving {
        assertNotNull(view.serving)
        return view.serving!!
    }

    private fun reading(neighbors: List<CellNeighbor> = listOf(CellNeighbor(CellGen.LTE, 12, 5110, -117))) = CellReading(
        registered = true,
        generation = CellGen.LTE,
        identity = CellIdentity(CellGen.LTE, "310-260", 4321, 98765L),
        pci = 50,
        channel = 5110,
        signal = -115,
        timingAdvance = 89,
        neighborCount = neighbors.size,
        neighbors = neighbors,
    )
}
