package dev.studiorizi.mterm.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Values and values-ja must define identical string keys. */
class StringsParityTest {

    private fun keys(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).mapTo(LinkedHashSet()) {
            nodes.item(it).attributes.getNamedItem("name").nodeValue
        }
    }

    @Test
    fun `values and values-ja share identical keys`() {
        val base = File("src/main/res/values/strings.xml")
        val ja = File("src/main/res/values-ja/strings.xml")
        assertEquals(keys(base), keys(ja))
    }
}
