package local.bilingual.reader
import local.bilingual.reader.reading.senseHeadings
import org.junit.Assert.assertEquals
import org.junit.Test


/** S5: the word card heads each part of a dictionary entry once. */
class SenseHeadingsTest {
    @Test
    fun headsEachPartOnceAndAnEntryWithOneLabelNotAtAll() {
        assertEquals(listOf("сущ.", null, "гл.", null, null), senseHeadings(listOf("сущ.", "сущ.", "гл.", "гл.", null)) { it })
        assertEquals(listOf(null, null, null), senseHeadings(listOf("сущ.", "сущ.", null)) { it })
    }
}
