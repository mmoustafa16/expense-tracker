package expense.sms

fun interface SmsSource {
    fun messages(): List<InboundSms>
}
