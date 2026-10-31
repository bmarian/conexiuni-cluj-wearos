package online.bmarian.bus

import online.bmarian.bus.logic.editDistance
import online.bmarian.bus.logic.matching
import online.bmarian.bus.logic.nameMatches
import online.bmarian.bus.logic.searchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases of nameMatching.spec.ts. */
class NameMatchTest {
    private fun matches(name: String, query: String, typos: Boolean = false) = nameMatches(searchKey(name), searchKey(query), typos)

    @Test
    fun takesTheWordsInAnyOrder() = assertTrue(matches("Bulevardul 21 Decembrie 1989", "decembrie 21"))

    @Test
    fun readsShortenedWordsAsWhatTheyStandFor() {
        assertTrue(matches("P-ta M. Viteazu", "piata mihai viteazu"))
        assertTrue(matches("P-ța Unirii", "piata unirii"))
        assertTrue(matches("C-tin Brâncoveanu Est", "constantin brancoveanu"))
        assertTrue(matches("B-dul Muncii", "bulevardul muncii"))
        assertTrue(matches("M.Viteazu", "mihai viteazu"))
        assertTrue(matches("Str. Unirii", "strada unirii"))
    }

    @Test
    fun findsAWordAfterTheMetropolitanMark() = assertTrue(matches("M-Școala Gimnazială", "scoala"))

    @Test
    fun findsWhatVoiceSpellsOutInFull() {
        assertTrue(matches("P-ta M. Viteazul", "Piața Mihai Viteazul"))
        assertTrue(matches("Str. E. Quinet", "strada edgar quinet"))
    }

    @Test
    fun needsNoKindOfStreetTheNameDoesNotHave() {
        assertTrue(matches("Horea", "strada horea"))
        assertTrue(matches("Horea", "bd horea"))
    }

    @Test
    fun doesNotTakeAnInitialAloneForAWord() {
        assertFalse(matches("P-ta M. Viteazu", "marasti"))
        assertFalse(matches("Str. Unirii", "strazi"))
    }

    @Test
    fun needsEveryWord() = assertFalse(matches("Iulius Mall Est", "iulius vest"))

    @Test
    fun forgivesATypoOnlyWhenAsked() {
        assertFalse(matches("Iulius Mall Est", "iulus"))
        assertTrue(matches("Iulius Mall Est", "iulus", typos = true))
        assertTrue(matches("Memorandumului Sud", "memorandomulu", typos = true))
    }

    @Test
    fun keepsShortWordsAndNumbersExact() {
        assertFalse(matches("Bar", "car", typos = true))
        assertFalse(matches("Linia 25", "24", typos = true))
        assertFalse(matches("Scoala 2019", "2018", typos = true))
    }

    @Test
    fun countsASwapAsOneAndGivesUpPastTheLimit() {
        assertEquals(1, editDistance("lidl", "lild", 2))
        assertEquals(2, editDistance("kaufland", "lidl", 1))
    }

    @Test
    fun ranksWhatTheNameHoldsWholeFirst() {
        assertEquals(listOf(3, 0), matching(listOf("P-ta M. Viteazu", "Mihai Viteazu"), ::searchKey, "mihai viteazu").map { it.rank })
    }

    @Test
    fun triesTyposOnlyWithNoOtherMatch() {
        assertEquals(listOf("Iulis"), matching(listOf("Iulius Mall", "Iulis"), ::searchKey, "iulis").map { it.item })
        assertEquals(listOf("Iulius Mall"), matching(listOf("Iulius Mall"), ::searchKey, "iulis").map { it.item })
    }
}
