package expense.intelligence

/**
 * Verified institutions are data for the shared pipeline.
 * Adding a bank is another [RegisteredSender]. It is not a new parser,
 * classifier, or extractor.
 *
 * CIB, ALEXBANK, and Vodafone are the first institutions confirmed on the
 * current device. They use this list, the same as any later institution.
 * A sender alias is added only when it was copied from that device. This
 * list does not guess one. An alias that is not here stays unknown and
 * cannot post.
 */
object InstitutionBootstrap {
    val records: List<RegisteredSender> = emptyList()
}
