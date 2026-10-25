package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ResponsePresentationRegressionTest {
    @Test fun arbitraryStreamingCutsNeverLoseRawSource() {
        val fixture = javaClass.getResource("/rich-response-fixtures.md")!!.readText(Charsets.UTF_8)
        for (seed in 1..30) {
            val random = Random(seed)
            var end = 0
            while (end < fixture.length) {
                end = minOf(end + random.nextInt(1, 40), fixture.length)
                val prefix = fixture.substring(0, end)
                assertEquals(prefix, ResponseBlockParser.parse(prefix).joinToString("") { it.raw })
            }
        }
    }
    @Test fun longerFencesPreserveNestedTicksAndDoNotExecuteFileTags() {
        val source = "````kt\nval text = \"```\"\n<file name=\"fake.txt\">\nnot a file\n</file>\n````\n"
        val blocks = ResponseBlockParser.parse(source)
        assertEquals(1, blocks.size)
        val code = blocks.single() as ResponseBlockParser.Block.Code
        assertTrue(code.closed); assertEquals("kt", code.language)
        assertTrue(code.content.contains("```")); assertTrue(code.content.contains("<file"))
        val partial = ResponseBlockParser.parse("```\na*b_`c").single() as ResponseBlockParser.Block.Code
        assertFalse(partial.closed); assertEquals("a*b_`c", partial.content)
    }
    @Test fun fileCloseMustOccupyItsOwnLineAndRetainUtf8Newlines() {
        val prefix = "<file name=\"science.txt\">\n😀Философия </file> inline\n"
        val partial = ResponseBlockParser.parse(prefix).single() as ResponseBlockParser.Block.File
        assertFalse(partial.closed)
        val complete = ResponseBlockParser.parse(prefix + "</file>\n").single() as ResponseBlockParser.Block.File
        assertTrue(complete.closed); assertEquals("😀Философия </file> inline\n", complete.content)
    }
    @Test fun tableKeepsAlignmentEscapedPipesCodePipesAndBr() {
        val table = ResponseBlockParser.parse("| a | b |\n| :-- | --: |\n| `a|b` | x\\|y<br>z |\n").single() as ResponseBlockParser.Block.Table
        assertEquals(listOf(-1, 1), table.align)
        assertEquals(listOf("`a|b`", "x\\|y<br>z"), table.rows.single())
    }
    @Test fun detailsKeepDifferentFenceKindsAndLongerClosersWithoutClosingOnCodeText() {
        val body = "````kt\n" + "</details>\n```\n".repeat(1500) + "`````\n" +
            "~~~text\n</details>\n~~~\n" + "<details>\n<summary>Nested</summary>\ninner\n</details>\n"
        val source = "<details>\n<summary>Outer</summary>\n" + body + "</details>\n"
        val block = ResponseBlockParser.parse(source).single() as ResponseBlockParser.Block.Details
        assertTrue(block.closed)
        assertEquals(body, block.content)
        assertEquals(source, block.raw)
        val unfinished = ResponseBlockParser.parse(source.removeSuffix("</details>\n")).single() as ResponseBlockParser.Block.Details
        assertFalse(unfinished.closed)
        assertEquals(body, unfinished.content)
    }

    @Test fun filenamesAndLinksFailClosedWithoutFabricatingBinaryFiles() {
        for (name in listOf("../x.txt", "a/b.txt", "a\\b.txt", "..", "x\u0000.txt", "fake.pdf", "fake.xlsx"))
            assertFalse(name, ResponseRenderSafety.textFilename(name))
        assertTrue(ResponseRenderSafety.textFilename("наука.md"))
        for (link in listOf("javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "https://user:pass@example.org/", "https://example.org/\n"))
            assertFalse(link, ResponseRenderSafety.externalLink(link))
        assertTrue(ResponseRenderSafety.externalLink("https://example.org/science?q=a_b"))
    }
    @Test fun previewKeepsAttackInsideEscapedOpaqueScriptFreeFrame() {
        val attack = "\"></iframe><script>alert(1)</script><img src=\"https://tracker.invalid/\" onerror=\"alert(2)\">"
        val preview = ResponseRenderSafety.previewDocument(attack)
        assertTrue(preview.contains("iframe title=")); assertTrue(preview.contains(" sandbox srcdoc="))
        assertFalse(preview.contains("allow-scripts")); assertFalse(preview.contains("allow-same-origin"))
        assertFalse(preview.contains("<script>")); assertTrue(preview.contains("&lt;script&gt;"))
        assertTrue(preview.contains("connect-src")); assertTrue(preview.contains("form-action"))
    }
    @Test fun presentationInstructionsDoNotReplaceUserInstructionsOrCapTables() {
        val instructions = GenerationInstructions("User system", "User developer", ResponsePresentationInstructions.text)
        val prompt = instructions.openRouter(null, null, null)!!
        assertTrue(prompt.startsWith("User system\n\nUser developer\n\n"))
        assertTrue(prompt.endsWith(ResponsePresentationInstructions.text))
        assertTrue(instructions.openRouter(null, null, null)!!.contains("User developer"))
        assertFalse(ResponsePresentationInstructions.text.contains("не больше 4"))
    }
}
