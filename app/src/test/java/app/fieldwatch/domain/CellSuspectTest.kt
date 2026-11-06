package app.fieldwatch.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellSuspectTest {
    @Test
    fun aTestNetworkWarnsAloneEvenWithNoFix() {
        val step = tick(CellBook(), lte(plmn = "001-01"), CellFix(), 1_000L)
        val episode = step.fresh.single()
        assertEquals(CellKind.SUSPECT, episode.kind)
        assertTrue(CellFlag.TEST_NETWORK.name in episode.flags)
        assertEquals("", episode.placeId)
        assertEquals(episode, CellWatch.showing(step.book))
    }

    @Test
    fun theSwitchOffScoresButDoesNotWarn() {
        val step = tick(CellBook(), lte(plmn = "001-01"), still(), 1_000L, warnSuspect = false)
        assertTrue(step.fresh.isEmpty())
        assertTrue(step.verdict.alarming)
        assertEquals(1, step.book.seen.size)
    }

    @Test
    fun theMasterSwitchOffDoesNotScoreOrRemember() {
        val step = tick(CellBook(), lte(plmn = "001-01"), still(), 1_000L, watch = false)
        assertTrue(step.fresh.isEmpty())
        assertTrue(step.book.seen.isEmpty())
    }

    @Test
    fun anOrdinaryCellIsQuiet() {
        val first = tick(CellBook(), lte(), moving(), 1_000L)
        assertTrue(first.fresh.isEmpty())
        val again = tick(first.book, lte(), moving(), 2_000L)
        assertTrue(again.fresh.isEmpty())
        assertTrue(again.verdict.flags.isEmpty())
    }

    @Test
    fun aKnownCellWithANewPciAndChannelWarnsButAPciAloneDoesNot() {
        val known = tick(CellBook(), lte(), moving(), 1_000L).book
        val pciOnly = tick(known, lte(pci = 77), moving(), 2_000L)
        assertTrue(pciOnly.fresh.isEmpty())
        assertEquals(listOf(CellFlag.PCI_CHANGED), pciOnly.verdict.flags)
        val clone = tick(known, lte(pci = 77, channel = 2050), moving(), 2_000L)
        val flags = clone.fresh.single().flags
        assertTrue(CellFlag.PCI_CHANGED.name in flags)
        assertTrue(CellFlag.CHANNEL_CHANGED.name in flags)
        // The ledger keeps the first values until Learn.
        assertEquals(50, clone.book.seen.single().pci)
    }

    @Test
    fun twoGWithAUsableFourGNeighborWarns() {
        val neighbors = listOf(CellNeighbor(CellGen.LTE, pci = 12, channel = 5230, signal = -95))
        val step = tick(CellBook(), gsm(neighbors), moving(), 1_000L)
        val flags = step.fresh.single().flags
        assertTrue(CellFlag.FORCED_DOWN.name in flags)
        assertTrue(CellFlag.PLAIN_2G.name in flags)
    }

    @Test
    fun twoGWithAFaintNeighborDoesNotWarn() {
        val neighbors = listOf(CellNeighbor(CellGen.LTE, signal = -121))
        val step = tick(CellBook(), gsm(neighbors), moving(), 1_000L)
        assertTrue(step.fresh.isEmpty())
        assertEquals(listOf(CellFlag.PLAIN_2G), step.verdict.flags)
    }

    @Test
    fun areaChurnCountsOnlyWhileStill() {
        var book = tick(CellBook(), lte(cell = 1, area = 1001), moving(), 1_000L).book
        book = tick(book, lte(cell = 2, area = 1002), moving(), 2_000L).book
        val drove = tick(book, lte(cell = 3, area = 1003), still(), 3_000L)
        assertFalse(CellFlag.AREA_CHURN in drove.verdict.flags)

        var sat = tick(CellBook(), lte(cell = 1, area = 1001), still(), 1_000L).book
        sat = tick(sat, lte(cell = 2, area = 1002), still(), 60_000L).book
        val churn = tick(sat, lte(cell = 3, area = 1003), still(), 120_000L)
        assertTrue(CellFlag.AREA_CHURN in churn.verdict.flags)
    }

    @Test
    fun aReservedAreaCodeIsABadIdentity() {
        assertTrue(CellSuspect.badIdentity(CellIdentity(CellGen.LTE, "310-410", 0xFFFE, 100L), 50))
        assertTrue(CellSuspect.badIdentity(CellIdentity(CellGen.LTE, "310-410", 1000, 100L), 600))
        assertTrue(CellSuspect.badIdentity(CellIdentity(CellGen.GSM, "310-410", 0, 100L), 5))
        assertFalse(CellSuspect.badIdentity(CellIdentity(CellGen.LTE, "310-410", 1000, 100L), 503))
        assertFalse(CellSuspect.badIdentity(CellIdentity(CellGen.NR, "310-410", 70000, 1L shl 35), 1007))
    }

    @Test
    fun aRepeatTouchesOneEpisodeAndLearnStopsIt() {
        val known = tick(CellBook(), lte(), moving(), 1_000L).book
        val first = tick(known, lte(pci = 77, channel = 2050), moving(), 2_000L)
        val again = tick(first.book, lte(pci = 77, channel = 2050), moving(), 3_000L)
        assertTrue(again.fresh.isEmpty())
        assertEquals(1, again.book.episodes.count { it.open })
        val id = again.book.episodes.single { it.open }.id
        val learned = CellWatch.learn(again.book, id, 4_000L)
        assertTrue(learned.episodes.none { it.open })
        assertEquals(77, learned.seen.single().pci)
        val after = tick(learned, lte(pci = 77, channel = 2050), moving(), 5_000L)
        assertTrue(after.fresh.isEmpty())
        assertTrue(after.verdict.flags.isEmpty())
    }

    @Test
    fun dismissHoldsTheCellAndItAgesOutAfterLeaving() {
        val first = tick(CellBook(), lte(plmn = "001-01"), moving(), 1_000L)
        val id = first.fresh.single().id
        val held = CellWatch.dismiss(first.book, id, 2_000L)
        assertFalse(held.dropHeld)
        val again = tick(held, lte(plmn = "001-01"), moving(), 3_000L)
        assertTrue(again.fresh.isEmpty())

        val left = tick(first.book, lte(), moving(), 2_000L + CellWatch.GONE_MS)
        assertTrue(left.book.episodes.none { it.open })
    }

    @Test
    fun aSuspectEpisodeDoesNotEndADropOrTheOtherWay() {
        val book = CellBook(
            episodes = listOf(
                CellEpisode(1, CellKind.DROP2, "p1", CellIdentity(CellGen.GSM, "310-410", 1000, 40L), startedAt = 1L),
            ),
            dropHeld = false,
            nextEpisode = 2,
        )
        val step = tick(book, lte(plmn = "001-01"), moving(), 1_000L)
        assertEquals(2, step.book.episodes.count { it.open })
        assertEquals(CellKind.SUSPECT, CellWatch.showing(step.book)?.kind)
    }

    @Test
    fun marksCarryTheReasons() {
        val step = tick(CellBook(), lte(plmn = "001-01"), moving(), 1_000L)
        val mark = CellWatch.marks(step.book).single()
        assertEquals("suspect", mark.kind)
        assertTrue(CellFlag.TEST_NETWORK.name in mark.flags)
        val block = CellWatch.block(listOf(mark), 0L, 2_000L, demo = false).orEmpty()
        assertTrue(block.contains(LanguageText.text("cell.flag.test.network")))
    }

    @Test
    fun anOldBookLoadsWithAnEmptyLedger() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString(CellBook.serializer(), """{"format":"fieldwatch-cells","episodes":[{"id":1,"kind":"NEW","placeId":"p1","identity":{"gen":"LTE","plmn":"310-410","area":1,"cell":2}}]}""")
        assertTrue(old.seen.isEmpty())
        assertFalse(old.sawNeighbors)
        assertTrue(old.episodes.single().flags.isEmpty())
    }

    @Test
    fun everyFlagHasEnglishCopy() {
        for (flag in CellFlag.entries) {
            assertTrue(flag.textKey(), EnglishCopy.byKey.containsKey(flag.textKey()))
        }
    }

    private fun tick(
        book: CellBook,
        reading: CellReading,
        fix: CellFix,
        now: Long,
        watch: Boolean = true,
        warnSuspect: Boolean = true,
    ) = CellWatch.tick(book, reading, fix, CellSwitches(watch, true, true, 0, warnSuspect), now)

    private fun lte(
        cell: Long = 100L,
        plmn: String = "310-410",
        area: Int = 1000,
        pci: Int? = 50,
        channel: Int = 5230,
    ) = CellReading(
        registered = true,
        generation = CellGen.LTE,
        identity = CellIdentity(CellGen.LTE, plmn, area, cell),
        pci = pci,
        channel = channel,
        signal = -95,
        timingAdvance = 3,
        neighborCount = 2,
    )

    private fun gsm(neighbors: List<CellNeighbor>) = CellReading(
        registered = true,
        generation = CellGen.GSM,
        identity = CellIdentity(CellGen.GSM, "310-410", 1000, 40L),
        pci = 7,
        channel = 128,
        signal = -80,
        timingAdvance = 3,
        neighborCount = neighbors.size,
        neighbors = neighbors,
    )

    private fun still() = CellFix(LAT, LON, 8f, 0f, paused = false)

    private fun moving() = CellFix(LAT, LON, 8f, 5f, paused = false)

    private companion object {
        const val LAT = 28.0
        const val LON = -81.0
    }
}
