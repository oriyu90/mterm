package dev.studiorizi.mterm.full

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guarantees JA/EN completeness: values/strings.xml and values-ja/strings.xml
 * must declare identical key sets so no locale shows a missing resource.
 * Pure JVM XML parse, no Android framework needed.
 */
class StringsParityTest {

    private fun keys(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).mapTo(LinkedHashSet()) { i ->
            nodes.item(i).attributes.getNamedItem("name").nodeValue
        }
    }

    @Test
    fun englishAndJapaneseDefineSameKeys() {
        val base = File("src/main/res/values/strings.xml")
        val japanese = File("src/main/res/values-ja/strings.xml")
        assertTrue("missing ${base.path}", base.isFile)
        assertTrue("missing ${japanese.path}", japanese.isFile)

        val en = keys(base)
        val ja = keys(japanese)
        assertEquals("JA/EN string key mismatch", en, ja)
        assertTrue("string resources must not be empty", en.isNotEmpty())
    }
}
