package javaScriptParsingExample

import Parser
import alpha
import between
import bind
import char
import decimal
import defer
import delimitedBy
import digit
import except
import fail
import item
import many
import map
import optional
import or
import parse
import pure
import skipLeft
import skipRight
import symbol
import text
import token
import whitespace

class JSParser {
    private val nullP = symbol("null").map { JSToken.JSNull }.token()
    private val boolP = symbol("true").map { true }.or(symbol("false").map { false }).map { JSToken.JSBoolean(it) }.token()
    private val stringP =
        char('\\').bind { item.map { c -> c } }.or(item.except(char('"'))).many()
            .between(char('"'), char('"'))
        .or(
        char('\\').bind { item.map { c -> c } }.or(item.except(char('\''))).many()
            .between(char('\''), char('\''))
        )
        .text()
        .map { JSToken.JSString(it) }
        .token()
    private val numP = decimal.map { JSToken.JSNumber(it) }.token()
    private val literalP = nullP.or(boolP).or(stringP).or(numP)
    private val identifierP = alpha.or(char('_')).bind { x -> alpha.or(char('_')).or(digit).many().text().map { xs -> x + xs } }.token()
    private fun jsExpression() : Parser<JSToken> = arithmeticP().or(literalP).or(arrayP).or(objectP).or(lambdaP).or(functionCallP)
    private val assignmentP =
        defer {
            symbol("const").token().skipLeft(
                identifierP.skipRight(char('=').token()).bind { left ->
                    jsExpression().token().map { right -> JSToken.JSAssignment(left, right) }
                })
                .skipRight(char(';').token())
        }

    private val arrayP =
        defer(::jsExpression)
            .delimitedBy(char(',').token())
            .optional()
            .between(char('['), char(']'))
            .map { JSToken.JSArray(it.valueOrDefault(listOf())) }
            .token()

    private val objectP =
        identifierP.map { JSToken.JSString(it) }.or(stringP).skipRight(char(':').token())
            .bind { key -> jsExpression().map { value -> Pair(key.value, value) } }
            .delimitedBy(char(',').token())
            .optional()
            .between(char('{').token(), char('}').token())
            .map { JSToken.JSObject(it.valueOrDefault(listOf())) }
            .token()

    private val lambdaP =
        defer {
            val argList = identifierP.delimitedBy(char(',').token()).optional().map { it.valueOrDefault(listOf()) }
                .between(char('(').token(), char(')').token())
            val returnP = symbol("return").token().skipLeft(jsExpression()).map { JSToken.JSReturn(it) }.skipRight(char(';').token())
            val body = jsTokenPs()
                .bind { assignments ->
                    returnP.map { assignments + it }
                }.between(char('{').token(), char('}').token())
            argList.skipRight(symbol("=>").token()).bind { args ->
                body .map { JSToken.JSLambda(args, it) }
            }
        }

    private val functionCallP = identifierP.bind { id ->
        jsExpression().delimitedBy(char(',').token()).map { JSToken.FunctionCall(id, it) }
            .between(char('(').token(), char(')').token())
    }

    private fun arithmeticP(): Parser<JSToken> {
        fun binaryOpParser(lowerPriority: Parser<JSToken>, operators: List<Parser<JSToken.BinaryOperator>>): Parser<JSToken> =
            lowerPriority.bind { f ->
                operators.map { it.token() }.fold(fail<JSToken.BinaryOperator>()) { a, b -> a.or(b) }.bind { operator ->
                    binaryOpParser(lowerPriority, operators).map { t -> JSToken.Expr.Binary(f, operator, t) }
                }.or(pure(f))
            }
        val factor = defer(::arithmeticP).between(char('(').token(), char(')').token()).or(numP)
        val exp = binaryOpParser(factor, listOf(char('^').map { JSToken.BinaryOperator.Exponent }))
        val term = binaryOpParser(exp, listOf(char('*').map { JSToken.BinaryOperator.Mul }, char('/').map { JSToken.BinaryOperator.Div }))
        val expr = binaryOpParser(term, listOf(char('+').map { JSToken.BinaryOperator.Add }, char('-').map { JSToken.BinaryOperator.Sub }))
        return expr
    }

    private fun jsTokenPs() = assignmentP.many().skipRight(whitespace.many())

    fun parse(input: String) = jsTokenPs().parse(input)
}