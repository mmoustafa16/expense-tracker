package expense.parse

class BankMatcher(
    private val registry: BankRegistry,
) {
    fun match(sender: String): List<BankProfile> {
        return registry.profiles.filter { it.accepts(sender) }
    }
}
