package expense.ledger

import expense.money.Money
import expense.parse.TransactionKind
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

object DuplicateMatcher {
    val bodyReplayWindow: Duration = Duration.ofMinutes(15)
    val nearDuplicateWindow: Duration = Duration.ofMinutes(5)

    fun providerReplay(messages: List<StoredSms>, providerMessageId: String?): StoredSms? {
        if (providerMessageId.isNullOrBlank()) return null
        return messages.find { it.providerMessageId == providerMessageId }
    }

    fun bodyReplay(
        messages: List<StoredSms>,
        sender: String,
        bodyHash: String,
        receivedAt: Instant,
    ): StoredSms? {
        return messages.find { message ->
            message.sender == sender &&
                message.bodyHash == bodyHash &&
                abs(Duration.between(message.receivedAt, receivedAt).toMinutes()) <= bodyReplayWindow.toMinutes()
        }
    }

    fun referenceResend(
        transactions: List<Transaction>,
        institutionId: String,
        reference: String?,
        kind: TransactionKind,
    ): Transaction? {
        if (reference.isNullOrBlank()) return null
        return transactions.find { tx ->
            tx.institutionId == institutionId &&
                tx.reference == reference &&
                tx.kind == kind
        }
    }

    fun nearMatches(
        transactions: List<Transaction>,
        accounts: List<Account>,
        smsHashes: Map<String, String>,
        candidateSmsHash: String,
        institutionId: String,
        mask: String?,
        amount: Money,
        kind: TransactionKind,
        occurredAt: Instant,
        reference: String?,
    ): List<Transaction> {
        return transactions.filter { tx ->
            val sameReference = !reference.isNullOrBlank() && tx.reference == reference
            tx.kind == kind &&
                tx.institutionId == institutionId &&
                accountMask(tx, accounts) == mask &&
                tx.amount == amount &&
                !sameReference &&
                smsHashes[tx.smsId] != candidateSmsHash &&
                abs(Duration.between(tx.occurredAt, occurredAt).toMillis()) <= nearDuplicateWindow.toMillis()
        }
    }

    fun accountMask(transaction: Transaction, accounts: List<Account>): String? {
        return accounts.find { it.id == transaction.accountId }?.mask
    }
}
