package expense.sms

fun interface SmsSource {
    fun messages(): List<InboundSms>

    /**
     * Hands messages to [accept] in lists no larger than [pageSize].
     * The default reads [messages] first. An inbox scan overrides this and
     * does not build one list of the whole inbox.
     */
    fun forEachPage(pageSize: Int, accept: (List<InboundSms>) -> Unit) {
        SmsPages.consume(messages().iterator(), pageSize, accept)
    }
}

object SmsPages {
    const val DEFAULT_PAGE_SIZE: Int = 40

    fun <T> consume(items: Iterator<T>, pageSize: Int, accept: (List<T>) -> Unit) {
        require(pageSize > 0)
        val page = ArrayList<T>(pageSize)
        while (items.hasNext()) {
            page.add(items.next())
            if (page.size == pageSize) {
                accept(page.toList())
                page.clear()
            }
        }
        if (page.isNotEmpty()) accept(page.toList())
    }
}
