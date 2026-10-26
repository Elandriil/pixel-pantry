package app.pixelpantry.tv.player

import app.pixelpantry.tv.api.ItemDetail
import app.pixelpantry.tv.api.ItemSummary
import app.pixelpantry.tv.api.SubtitleTrack
import app.pixelpantry.tv.api.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePlanTest {
    private val base: (String) -> String = { "http://10.0.2.2:8765$it" }

    private val embedded3 = SubtitleTrack(id = "embedded:3", label = "English", lang = "eng", format = "embedded")
    private val embedded4 = SubtitleTrack(id = "embedded:4", label = "Deutsch", lang = "ger", format = "embedded", isDefault = true)
    private val srt = SubtitleTrack(
        id = "sidecar:Show%20S01E01.en.srt", label = "English (srt)", lang = "en",
        url = "/api/items/4/subtitles/sidecar/Show%20S01E01.en.srt", format = "srt",
    )
    private val vtt = SubtitleTrack(
        id = "sidecar:Show%20S01E01.fr.vtt", label = "French (vtt)", lang = "fr",
        url = "/api/items/4/subtitles/sidecar/Show%20S01E01.fr.vtt", format = "vtt",
    )
    private val ass = SubtitleTrack(
        id = "sidecar:Show%20S01E01.ass", label = "ass", url = "/api/items/4/subtitles/sidecar/Show%20S01E01.ass", format = "ass",
    )

    private fun detail(
        subtitles: List<SubtitleTrack> = listOf(embedded3, embedded4, srt, vtt, ass),
        audio: List<Track> = listOf(Track(index = 1, codec = "aac", language = "eng", isDefault = true), Track(index = 2, codec = "ac3", language = "ger")),
        audioChoice: String? = null,
    ) = ItemDetail(
        item = ItemSummary(id = 4, type = "episode", category = "tv", title = "Pilot"),
        streamUrl = "/api/items/4/stream",
        fileName = "Show S01E01.mkv",
        audio = audio,
        subtitles = subtitles,
        audioChoice = audioChoice,
    )

    @Test fun sidecarSrtIsSubripWithTheBasePrefixedUri() {
        val plan = SubtitlePlan.build(detail(), base, srt.id, shiftMs = 0)
        val c = plan.subtitleConfigs.first { it.id == srt.id }
        assertEquals("application/x-subrip", c.mimeType)
        assertEquals("http://10.0.2.2:8765/api/items/4/subtitles/sidecar/Show%20S01E01.en.srt", c.uri)
        assertEquals("English (srt)", c.label)
        assertEquals("en", c.language)
        assertTrue(c.selected)
        assertEquals(c.uri, plan.selectedSidecarUri)
        assertNull(plan.selectedEmbeddedIndex)
        assertEquals(srt.id, plan.subtitleChoice)
    }

    @Test fun everySidecarIsConfiguredButOnlyTheChosenOneIsSelected() {
        val plan = SubtitlePlan.build(detail(), base, vtt.id, shiftMs = 0)
        assertEquals(listOf(srt.id, vtt.id, ass.id), plan.subtitleConfigs.map { it.id })
        assertEquals(listOf(false, true, false), plan.subtitleConfigs.map { it.selected })
        assertEquals("text/vtt", plan.subtitleConfigs[1].mimeType)
    }

    @Test fun shiftIsAppendedOnlyToTheChosenSidecar() {
        val plan = SubtitlePlan.build(detail(), base, srt.id, shiftMs = 500)
        assertEquals("http://10.0.2.2:8765/api/items/4/subtitles/sidecar/Show%20S01E01.en.srt?shift=500", plan.subtitleConfigs[0].uri)
        assertEquals(plan.subtitleConfigs[0].uri, plan.selectedSidecarUri)
        assertEquals("http://10.0.2.2:8765/api/items/4/subtitles/sidecar/Show%20S01E01.fr.vtt", plan.subtitleConfigs[1].uri)
        assertFalse(plan.subtitleConfigs[2].uri.contains("shift"))
    }

    @Test fun negativeShiftIsAppendedToo() {
        val plan = SubtitlePlan.build(detail(), base, srt.id, shiftMs = -1000)
        assertTrue(plan.subtitleConfigs[0].uri.endsWith("?shift=-1000"))
    }

    @Test fun embeddedChoiceSelectsTheStreamIndexAndNoSidecar() {
        val plan = SubtitlePlan.build(detail(), base, "embedded:3", shiftMs = 0)
        assertEquals(3, plan.selectedEmbeddedIndex)
        assertNull(plan.selectedSidecarUri)
        assertTrue(plan.subtitleConfigs.none { it.selected })
        assertEquals("embedded:3", plan.subtitleChoice)
    }

    @Test fun shiftNeverAppliesToAnEmbeddedChoice() {
        val plan = SubtitlePlan.build(detail(), base, "embedded:3", shiftMs = 500)
        assertTrue(plan.subtitleConfigs.none { it.uri.contains("shift") })
    }

    @Test fun offSelectsNothing() {
        val plan = SubtitlePlan.build(detail(), base, "off", shiftMs = 0)
        assertNull(plan.selectedEmbeddedIndex)
        assertNull(plan.selectedSidecarUri)
        assertTrue(plan.subtitleConfigs.none { it.selected })
        assertEquals("off", plan.subtitleChoice)
    }

    @Test fun anUnknownStoredChoiceFallsBackToTheDefaultEmbeddedTrack() {
        val plan = SubtitlePlan.build(detail(), base, "sidecar:gone.srt", shiftMs = 0)
        assertEquals(4, plan.selectedEmbeddedIndex)
        assertNull(plan.selectedSidecarUri)
        assertEquals("embedded:4", plan.subtitleChoice)
    }

    @Test fun noChoiceFallsBackToTheDefaultEmbeddedTrack() {
        assertEquals(4, SubtitlePlan.build(detail(), base, null, shiftMs = 0).selectedEmbeddedIndex)
    }

    @Test fun noDefaultEmbeddedTrackMeansOff() {
        val plan = SubtitlePlan.build(detail(subtitles = listOf(embedded3, srt)), base, null, shiftMs = 0)
        assertNull(plan.selectedEmbeddedIndex)
        assertNull(plan.selectedSidecarUri)
        assertEquals("off", plan.subtitleChoice)
    }

    @Test fun assIsSsa() {
        val plan = SubtitlePlan.build(detail(), base, ass.id, shiftMs = 0)
        assertEquals("text/x-ssa", plan.subtitleConfigs.first { it.id == ass.id }.mimeType)
        assertNull(plan.subtitleConfigs.first { it.id == ass.id }.language)
    }

    @Test fun audioRestoresAMatchingStoredChoiceElseTheDefaultTrack() {
        assertEquals("audio:2", SubtitlePlan.build(detail(audioChoice = "audio:2"), base, null, 0).audioChoice)
        assertEquals("audio:1", SubtitlePlan.build(detail(audioChoice = "audio:9"), base, null, 0).audioChoice)
        assertEquals("audio:1", SubtitlePlan.build(detail(), base, null, 0).audioChoice)
        val noDefault = listOf(Track(index = 2, codec = "aac"), Track(index = 3, codec = "ac3"))
        assertEquals("audio:2", SubtitlePlan.build(detail(audio = noDefault), base, null, 0).audioChoice)
        assertNull(SubtitlePlan.build(detail(audio = emptyList()), base, null, 0).audioChoice)
    }

    // Matching ids to the player's track groups (by order; see SubtitlePlan.textGroup)

    private fun text(group: Int, id: String?, default: Boolean = false) =
        PlayerTrack(groupIndex = group, id = id, language = null, label = null, isDefault = default, selected = false)

    private val playerText = listOf(text(1, "3"), text(2, "4", default = true), text(3, "sidecar:Show%20S01E01.en.srt"))

    @Test fun embeddedIdsMapToTheEmbeddedTextGroupsInStreamOrder() {
        val d = detail()
        assertEquals(1, SubtitlePlan.textGroup("embedded:3", d, playerText))
        assertEquals(2, SubtitlePlan.textGroup("embedded:4", d, playerText))
        assertNull(SubtitlePlan.textGroup("embedded:9", d, playerText))
    }

    /** What ExoPlayer reports once sidecars are merged in: period-prefixed format ids. */
    private val mergedText = listOf(text(1, "0:4"), text(2, "0:5", default = true), text(3, "1:sidecar:Show%20S01E01.en.srt"))

    @Test fun periodPrefixedIdsFromAMergedItemMapToo() {
        val d = detail()
        assertEquals(1, SubtitlePlan.textGroup("embedded:3", d, mergedText))
        assertEquals(2, SubtitlePlan.textGroup("embedded:4", d, mergedText))
        assertEquals(3, SubtitlePlan.textGroup(srt.id, d, mergedText))
        assertEquals("embedded:4", SubtitlePlan.defaultEmbeddedId(d.copy(subtitles = d.subtitles.map { it.copy(isDefault = false) }), mergedText))
    }

    @Test fun sidecarIdsMapToTheGroupWithTheirId() {
        assertEquals(3, SubtitlePlan.textGroup(srt.id, detail(), playerText))
        assertNull(SubtitlePlan.textGroup(vtt.id, detail(), playerText))
    }

    @Test fun thePlayersDefaultFlagNamesTheDefaultEmbeddedTrack() {
        val d = detail(subtitles = listOf(embedded3, embedded4.copy(isDefault = false), srt))
        assertEquals("embedded:4", SubtitlePlan.defaultEmbeddedId(d, playerText))
        assertNull(SubtitlePlan.defaultEmbeddedId(d, listOf(text(1, "3"), text(2, "4"))))
    }

    @Test fun audioIdsMapToTheAudioGroupsInStreamOrder() {
        val audio = listOf(text(0, "1"), text(4, "2"))
        assertEquals(0, SubtitlePlan.audioGroup("audio:1", detail(), audio))
        assertEquals(4, SubtitlePlan.audioGroup("audio:2", detail(), audio))
        assertNull(SubtitlePlan.audioGroup("audio:7", detail(), audio))
    }

    // When the player shows a different number of embedded text groups than the server lists

    private fun lang(group: Int, id: String?, language: String?, default: Boolean = false) =
        PlayerTrack(groupIndex = group, id = id, language = language, label = null, isDefault = default, selected = false)

    private val threeEmbedded = detail(
        subtitles = listOf(
            SubtitleTrack(id = "embedded:2", label = "English", lang = "eng", format = "embedded"),
            SubtitleTrack(id = "embedded:3", label = "English SDH", lang = "eng", format = "embedded"),
            SubtitleTrack(id = "embedded:4", label = "Deutsch", lang = "ger", format = "embedded"),
            srt,
        ),
    )

    @Test fun withAMissingGroupTracksMatchByLanguageAndPositionWithinIt() {
        // The player dropped the first English stream; ids are arbitrary here.
        val tracks = listOf(lang(5, "0:9", "en"), lang(6, "0:8", "de"), lang(7, "1:sidecar:Show%20S01E01.en.srt", "en"))
        assertEquals("first English group is matched to the first English track", 5, SubtitlePlan.textGroup("embedded:2", threeEmbedded, tracks))
        assertNull("no second English group", SubtitlePlan.textGroup("embedded:3", threeEmbedded, tracks))
        assertEquals("ger and de are the same language", 6, SubtitlePlan.textGroup("embedded:4", threeEmbedded, tracks))
    }

    @Test fun withoutLanguagesTracksMatchByIdOfStreamIndexPlusOne() {
        val noLang = threeEmbedded.copy(subtitles = threeEmbedded.subtitles.map { it.copy(lang = null) })
        val tracks = listOf(lang(5, "0:4", null), lang(6, "0:5", null))
        assertEquals(5, SubtitlePlan.textGroup("embedded:3", noLang, tracks))
        assertEquals(6, SubtitlePlan.textGroup("embedded:4", noLang, tracks))
        assertNull("nothing matches: nothing is selected", SubtitlePlan.textGroup("embedded:2", noLang, tracks))
    }

    @Test fun theDefaultFlagUsesTheSameGuard() {
        val tracks = listOf(lang(5, "0:9", "en"), lang(6, "0:8", "de", default = true))
        assertEquals("embedded:4", SubtitlePlan.defaultEmbeddedId(threeEmbedded, tracks))
    }

    @Test fun languageCodesNormalise() {
        assertEquals("en", SubtitlePlan.normalizeLanguage("eng"))
        assertEquals("en", SubtitlePlan.normalizeLanguage("en-US"))
        assertEquals("de", SubtitlePlan.normalizeLanguage("ger"))
        assertEquals("de", SubtitlePlan.normalizeLanguage("deu"))
        assertNull(SubtitlePlan.normalizeLanguage("und"))
        assertNull(SubtitlePlan.normalizeLanguage(null))
    }
}
