package expense.intelligence

/**
 * Optional exact sender records.
 * Production stays empty. A new institution does not need an entry here.
 *
 * An alphanumeric address is not registered here and is not treated as a
 * bank by itself. [InstitutionalSenderDiscovery] verifies it only when the
 * message states a card or account, or when that sender was remembered on
 * this device. [InstitutionCatalog] can supply a display name later without
 * a new parser. A handset number is not an institution and is never invented.
 */
object InstitutionBootstrap {
    val records: List<RegisteredSender> = emptyList()
}
