package expense.intelligence

/**
 * Verified institutions are data for the shared pipeline.
 * Adding a bank is another [RegisteredSender]. It is not a new parser,
 * classifier, or extractor.
 *
 * An alias is copied from the device SMS address shown in Review. It is an
 * exact trim match. This list does not guess one. An alias that is not here
 * stays unknown and cannot post.
 *
 * The device Review screen showed sender `CIB` on the card-charge and account
 * debit messages. ALEXBANK has no copied sender address in that evidence.
 * Sender `Vodafone` appeared on a mobile package renewal, which does not
 * establish a Vodafone Cash wallet address, so it is not registered.
 */
object InstitutionBootstrap {
    val records: List<RegisteredSender> = listOf(
        RegisteredSender(
            institutionId = "cib",
            displayName = "CIB",
            senderIds = setOf("CIB"),
        ),
    )
}
