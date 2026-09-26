package expense.money

data class Currency(
    val code: String,
    val minorUnits: Int,
) {
    init {
        require(code.length == 3 && code.all { it.isLetter() }) { "ISO currency code required" }
        require(minorUnits in 0..6) { "minor units out of range" }
    }

    companion object {
        val EGP: Currency = Currency("EGP", 2)
        val USD: Currency = Currency("USD", 2)
        val EUR: Currency = Currency("EUR", 2)
        val GBP: Currency = Currency("GBP", 2)

        fun of(code: String): Currency {
            return when (val normalized = code.trim().uppercase()) {
                "EGP" -> EGP
                "USD" -> USD
                "EUR" -> EUR
                "GBP" -> GBP
                else -> Currency(normalized, 2)
            }
        }
    }
}
