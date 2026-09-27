package expense.android.ui.common

import expense.money.Currency
import expense.money.Money

object MoneyFormat {
    fun format(signedMinor: Long, currency: Currency): String {
        val negative = signedMinor < 0
        val absolute = if (negative) -signedMinor else signedMinor
        val digits = digits(absolute, currency.minorUnits)
        val shown = if (negative) "-$digits" else digits
        return "$shown ${currency.code}"
    }

    fun format(money: Money): String = format(money.amountMinor, money.currency)

    private fun digits(absoluteMinor: Long, scale: Int): String {
        if (scale == 0) return absoluteMinor.toString()
        var factor = 1L
        repeat(scale) { factor *= 10L }
        val whole = absoluteMinor / factor
        val fraction = (absoluteMinor % factor).toString().padStart(scale, '0')
        return "$whole.$fraction"
    }
}
