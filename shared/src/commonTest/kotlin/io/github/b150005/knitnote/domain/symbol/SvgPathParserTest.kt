package io.github.b150005.knitnote.domain.symbol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SvgPathParserTest {
    @Test
    fun `empty path yields no commands`() {
        assertTrue(SvgPathParser.parse("").isEmpty())
        assertTrue(SvgPathParser.parse("   ").isEmpty())
    }

    @Test
    fun `simple move and line produce absolute commands`() {
        val result = SvgPathParser.parse("M 0.1 0.2 L 0.3 0.4")

        assertEquals(
            listOf(
                PathCommand.MoveTo(0.1, 0.2),
                PathCommand.LineTo(0.3, 0.4),
            ),
            result,
        )
    }

    @Test
    fun `relative commands resolve against current point`() {
        // Integer operands keep arithmetic exact so the assertion doesn't hinge on
        // IEEE 754 rounding.
        val result = SvgPathParser.parse("M 1 2 l 3 4")

        assertEquals(
            listOf(
                PathCommand.MoveTo(1.0, 2.0),
                PathCommand.LineTo(4.0, 6.0),
            ),
            result,
        )
    }

    @Test
    fun `H and V shorthand expand to LineTo with preserved axis`() {
        val result = SvgPathParser.parse("M 0.1 0.5 H 0.9 V 0.2")

        assertEquals(
            listOf(
                PathCommand.MoveTo(0.1, 0.5),
                PathCommand.LineTo(0.9, 0.5),
                PathCommand.LineTo(0.9, 0.2),
            ),
            result,
        )
    }

    @Test
    fun `implicit linetos after moveto use same command`() {
        // "M 0 0 1 1 2 2" = MoveTo(0,0), LineTo(1,1), LineTo(2,2)
        val result = SvgPathParser.parse("M 0 0 1 1 2 2")

        assertEquals(
            listOf(
                PathCommand.MoveTo(0.0, 0.0),
                PathCommand.LineTo(1.0, 1.0),
                PathCommand.LineTo(2.0, 2.0),
            ),
            result,
        )
    }

    @Test
    fun `close path returns current point to subpath start`() {
        val result = SvgPathParser.parse("M 0.2 0.2 L 0.8 0.2 L 0.8 0.8 Z L 0.9 0.9")

        val close = result.elementAt(3)
        assertTrue(close is PathCommand.ClosePath)
        // After Z, pen returned to 0.2,0.2; subsequent L targets 0.9,0.9.
        assertEquals(PathCommand.LineTo(0.9, 0.9), result.last())
    }

    @Test
    fun `cubic bezier round-trips the operands`() {
        val result =
            SvgPathParser.parse(
                "M 0 0 C 0.25 0.1 0.75 0.9 1 1",
            )

        assertEquals(
            PathCommand.CurveTo(0.25, 0.1, 0.75, 0.9, 1.0, 1.0),
            result[1],
        )
    }

    @Test
    fun `quadratic bezier parses`() {
        val result = SvgPathParser.parse("M 0 0 Q 0.5 1.0 1 0")

        assertEquals(PathCommand.QuadTo(0.5, 1.0, 1.0, 0.0), result[1])
    }

    @Test
    fun `smooth cubic reflects previous control point`() {
        // First C ends at (2,2) with c2=(1,1); S should reflect about (2,2)
        // yielding first control (3,3). Integer operands keep arithmetic exact.
        val result =
            SvgPathParser.parse(
                "M 0 0 C 0 0 1 1 2 2 S 2 3 4 4",
            )

        val second = result[2]
        assertTrue(second is PathCommand.CurveTo)
        assertEquals(3.0, second.c1x, "reflection x")
        assertEquals(3.0, second.c1y, "reflection y")
    }

    @Test
    fun `commas and signed floats tokenize`() {
        val result = SvgPathParser.parse("M.5,.5l-.25-.25")

        assertEquals(
            listOf(
                PathCommand.MoveTo(0.5, 0.5),
                PathCommand.LineTo(0.25, 0.25),
            ),
            result,
        )
    }

    @Test
    fun `unsupported command throws`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                SvgPathParser.parse("M 0 0 A 0.1 0.1 0 0 1 1 1")
            }
        val message = err.message!!
        assertTrue(message.contains("Unsupported"), "error mentions unsupported: $message")
        assertTrue(message.contains("'A'"), "error quotes the offending letter: $message")
    }

    @Test
    fun `truncated operand list throws`() {
        assertFailsWith<IllegalArgumentException> {
            SvgPathParser.parse("M 0 0 L 0.5")
        }
    }

    @Test
    fun `trailing command with no operands throws instead of silently dropping`() {
        // Guards against silently losing user-authored path segments if an
        // exporter emits a dangling command letter.
        val err =
            assertFailsWith<IllegalArgumentException> {
                SvgPathParser.parse("M 0 0 L")
            }
        assertTrue(err.message!!.contains("no operands"), "error mentions missing operands: ${err.message}")
    }

    @Test
    fun `trailing Z is allowed because it has zero operands`() {
        val result = SvgPathParser.parse("M 0 0 L 1 1 Z")
        assertTrue(result.last() is PathCommand.ClosePath)
    }
}
