package expense.intelligence

/**
 * Optional exact sender records.
 * Production stays empty. A new institution does not need an entry here.
 *
 * [InstitutionalSenderDiscovery] verifies an unambiguous alphanumeric sender
 * or short code from the message itself. [InstitutionCatalog] can supply a
 * display name later without a new parser. A handset number is not an
 * institution and is never invented.
 */
object InstitutionBootstrap {
    val records: List<RegisteredSender> = emptyList()
}
