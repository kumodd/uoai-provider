package com.uoai.plugins

import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Safe, deterministic, offline arithmetic evaluator (supports +, -, *, /, parentheses, and decimals).
 * Does not use reflection, scripting engines, or arbitrary code evaluation.
 */
object SafeCalculatorHandler : SafeToolHandler {
    override val pluginId = "builtin.utilities"
    override val toolName = "calculator"
    override val description = "Evaluate a basic arithmetic expression (+, -, *, /, parentheses)."
    override val inputSchemaJson = """{"type":"object","properties":{"expression":{"type":"string","description":"Arithmetic expression, e.g. (12.5 * 4) + 3"}},"required":["expression"]}"""

    override suspend fun execute(arguments: JsonObject): Result<String> = runCatching {
        val expr = arguments["expression"]?.jsonPrimitive?.contentOrNull
            ?: arguments["a"]?.jsonPrimitive?.doubleOrNull?.let { a ->
                val b = arguments["b"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val op = arguments["op"]?.jsonPrimitive?.contentOrNull ?: "+"
                "$a $op $b"
            }
            ?: throw IllegalArgumentException("Missing 'expression' field")
        require(expr.length <= 128 && expr.all { it.isDigit() || it in " +-*/.()" }) {
            "Disallowed characters in expression"
        }
        val value = Parser(expr).parse()
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }

    private class Parser(private val s: String) {
        private var pos = -1
        private var ch = 0
        private fun nextChar() { ch = if (++pos < s.length) s[pos].code else -1 }
        private fun eat(c: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == c) { nextChar(); return true }
            return false
        }
        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < s.length) throw IllegalArgumentException("Unexpected char: '${s[pos]}'")
            return x
        }
        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+'.code) -> x += parseTerm()
                    eat('-'.code) -> x -= parseTerm()
                    else -> return x
                }
            }
        }
        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*'.code) -> x *= parseFactor()
                    eat('/'.code) -> {
                        val d = parseFactor()
                        require(d != 0.0) { "Division by zero" }
                        x /= d
                    }
                    else -> return x
                }
            }
        }
        private fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()
            var x: Double
            val startPos = pos
            if (eat('('.code)) {
                x = parseExpression()
                require(eat(')'.code)) { "Missing closing parenthesis" }
            } else if (ch in '0'.code..'9'.code || ch == '.'.code) {
                while (ch in '0'.code..'9'.code || ch == '.'.code) nextChar()
                x = s.substring(startPos, pos).toDouble()
            } else {
                throw IllegalArgumentException("Unexpected token")
            }
            return x
        }
    }
}

/** Safe offline device summary tool (non-sensitive OS/hardware metadata + local timestamp). */
object DeviceInfoHandler : SafeToolHandler {
    override val pluginId = "builtin.utilities"
    override val toolName = "device_info"
    override val description = "Return non-sensitive offline device and runtime metadata (OS version, model, current local time)."
    override val inputSchemaJson = """{"type":"object","properties":{}}"""

    override suspend fun execute(arguments: JsonObject): Result<String> = runCatching {
        val now = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault()).format(Instant.now())
        """{"manufacturer":"${Build.MANUFACTURER}","model":"${Build.MODEL}","sdk_int":${Build.VERSION.SDK_INT},"local_time":"$now","offline_provider":"Universal Offline AI Provider"}"""
    }
}
