package expense.android.storage

import expense.money.Currency
import expense.money.Money

internal fun amountText(amount: Money): String = amountText(amount.amountMinor, amount.currency)

internal fun amountText(minor: Long, currency: Currency): String {
    val scale = currency.minorUnits
    if (scale == 0) return minor.toString()
    var factor = 1L
    repeat(scale) { factor *= 10L }
    val whole = minor / factor
    val fraction = (minor % factor).toString().padStart(scale, '0')
    return "$whole.$fraction"
}
