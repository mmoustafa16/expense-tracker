package expense.ingest

import expense.intelligence.SemanticTransactionClassifier

/**
 * Loads the on-device semantic model once.
 * Call this off the main thread at process start. Later classification reuses it.
 */
object SemanticsWarmup {
    fun start() {
        SemanticTransactionClassifier.bundled()
    }
}
