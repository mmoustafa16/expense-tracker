package expense.android.storage

import app.cash.sqldelight.db.SqlDriver
import expense.android.storage.db.ExpenseDatabase
import expense.categories.CategoryChange
import expense.categories.NewCategory
import expense.ingest.IdGenerator
import expense.ingest.IngestPipeline
import expense.ingest.IngestTally
import expense.ingest.PipelineMetadata
import expense.ingest.UuidIdGenerator
import expense.ledger.AccountNames
import expense.ledger.Correction
import expense.ledger.LedgerCategories
import expense.ledger.LedgerState
import expense.ledger.ManualDraft
import expense.ledger.ManualLedger
import expense.ledger.ReviewDismissals
import expense.sms.InboundSms
import expense.sms.SmsPages
import expense.sms.SmsSource
import java.io.File
import javax.crypto.Cipher

sealed class UnlockResult {
    data object Ready : UnlockResult()

    data object KeyUnavailable : UnlockResult()

    data object AuthenticationFailed : UnlockResult()
}

fun interface UnlockPrompt {
    fun authenticate(onSuccess: () -> Unit, onFailure: () -> Unit)

    /** True when this prompt can authorize a keystore cipher. */
    fun bindsCipher(): Boolean = false

    fun authorize(cipher: Cipher, onSuccess: (Cipher) -> Unit, onFailure: () -> Unit) {
        authenticate(onSuccess = { onSuccess(cipher) }, onFailure = onFailure)
    }
}

class DatabaseLockedException : IllegalStateException("ledger database is locked")

/**
 * One cold start unlocks the database. Later operations reuse the passphrase
 * held in this process and do not prompt again.
 */
class LedgerSession(
    private val databaseFile: File,
    private val vault: DatabaseKeyVault,
    private val openDriver: (File, ByteArray) -> SqlDriver,
    private val pipeline: IngestPipeline = IngestPipeline(),
    private val ids: IdGenerator = UuidIdGenerator,
) {
    private val lock = Any()
    private var passphrase: ByteArray? = null
    private var repository: SqlDelightLedgerRepository? = null
    private val pendingMessages = mutableListOf<InboundSms>()
    private val pendingSources = mutableListOf<SmsSource>()
    private var screenCache: LedgerState? = null
    private var reviewCacheKey: Pair<Int, Int>? = null
    private var reviewCache: ReviewWindow? = null

    fun isUnlocked(): Boolean = synchronized(lock) { passphrase != null }

    fun unlock(prompt: UnlockPrompt, onResult: (UnlockResult) -> Unit) {
        synchronized(lock) {
            if (passphrase != null) {
                onResult(UnlockResult.Ready)
                return
            }
        }
        var delivered = false
        fun deliver(result: UnlockResult) {
            if (delivered) return
            delivered = true
            onResult(result)
        }
        fun accept(material: KeyMaterial) {
            synchronized(lock) {
                if (passphrase != null) {
                    deliver(UnlockResult.Ready)
                    return
                }
                when (material) {
                    is KeyMaterial.Available -> {
                        passphrase = material.passphrase.copyOf()
                        flushLocked()
                        deliver(UnlockResult.Ready)
                    }
                    KeyMaterial.Unavailable -> deliver(UnlockResult.KeyUnavailable)
                    KeyMaterial.AuthenticationRequired -> deliver(UnlockResult.AuthenticationFailed)
                }
            }
        }
        fun acceptSafely(resolve: () -> KeyMaterial) {
            try {
                accept(resolve())
            } catch (_: UserAuthRequiredException) {
                deliver(UnlockResult.AuthenticationFailed)
            } catch (_: KeyUnrecoverableException) {
                deliver(UnlockResult.KeyUnavailable)
            }
        }
        if (!prompt.bindsCipher()) {
            prompt.authenticate(
                onSuccess = { acceptSafely { vault.readOrCreate(databaseFile.exists()) } },
                onFailure = { deliver(UnlockResult.AuthenticationFailed) },
            )
            return
        }
        val challenge = try {
            vault.challenge(databaseFile.exists())
        } catch (_: UserAuthRequiredException) {
            deliver(UnlockResult.AuthenticationFailed)
            return
        } catch (_: KeyUnrecoverableException) {
            deliver(UnlockResult.KeyUnavailable)
            return
        }
        when (challenge) {
            is KeyChallenge.Deferred -> prompt.authenticate(
                onSuccess = { acceptSafely(challenge.resolve) },
                onFailure = { deliver(UnlockResult.AuthenticationFailed) },
            )
            is KeyChallenge.NeedsCipher -> prompt.authorize(
                cipher = challenge.cipher,
                onSuccess = { authed -> acceptSafely { challenge.finish(authed) } },
                onFailure = { deliver(UnlockResult.AuthenticationFailed) },
            )
        }
    }

    fun accept(messages: List<InboundSms>) {
        if (messages.isEmpty()) return
        synchronized(lock) {
            if (passphrase == null) {
                pendingMessages += messages
            } else {
                writeLocked(messages)
            }
        }
    }

    fun ingest(source: SmsSource, onPage: (IngestTally) -> Unit = {}) {
        val queued = synchronized(lock) {
            if (passphrase == null) {
                pendingSources += source
                true
            } else {
                false
            }
        }
        if (queued) return
        stream(source, onPage)
    }

    fun reviewWindow(offset: Int, limit: Int): ReviewWindow {
        return synchronized(lock) {
            val key = offset to limit
            val cached = reviewCache
            if (reviewCacheKey == key && cached != null) return cached
            val window = repositoryLocked().reviewWindow(offset, limit)
            reviewCacheKey = key
            reviewCache = window
            window
        }
    }

    fun peekReview(offset: Int, limit: Int): ReviewWindow? {
        return synchronized(lock) {
            reviewCache?.takeIf { reviewCacheKey == offset to limit }
        }
    }

    fun snapshot(matches: List<SearchMatch>): LedgerState {
        return synchronized(lock) { repositoryLocked().snapshot(matches) }
    }

    fun storedTally(): IngestTally = synchronized(lock) { repositoryLocked().storedTally() }

    fun reclassifyRetained(pageSize: Int = SmsPages.DEFAULT_PAGE_SIZE, maxPages: Int = Int.MAX_VALUE): Int {
        return synchronized(lock) {
            val visited = repositoryLocked().reclassifyRetained(
                pageSize = pageSize,
                maxPages = maxPages,
                pipelineVersion = PipelineMetadata.VERSION,
                newAttemptId = ids::newId,
                interpret = pipeline::interpretStored,
                post = { state, message, decision ->
                    pipeline.postStored(state, message, checkNotNull(decision.profile), checkNotNull(decision.extraction))
                },
            )
            invalidateCaches()
            visited
        }
    }

    fun load(): LedgerState = synchronized(lock) { repositoryLocked().load() }

    /** Ledger, review navigation, and analytics. Does not read SMS bodies. */
    fun screen(): LedgerState = synchronized(lock) {
        screenCache ?: repositoryLocked().screenProjection().also { screenCache = it }
    }

    fun peekScreen(): LedgerState? = synchronized(lock) { screenCache }

    fun search(query: String): List<SearchMatch> = synchronized(lock) { repositoryLocked().search(query) }

    fun correct(correction: Correction): LedgerState = edit { pipeline.correct(it, correction) }

    fun addCategory(draft: NewCategory): LedgerState = edit { LedgerCategories.add(it, draft) }

    fun updateCategory(id: String, change: CategoryChange): LedgerState = edit {
        LedgerCategories.update(it, id, change)
    }

    fun renameAccount(accountId: String, displayName: String): LedgerState = edit {
        AccountNames.rename(it, accountId, displayName)
    }

    fun dismissReview(attemptId: String): LedgerState = edit { ReviewDismissals.dismiss(it, attemptId) }

    fun postManual(draft: ManualDraft): LedgerState = edit { ManualLedger.post(it, draft, ids::newId) }

    private fun edit(transform: (LedgerState) -> LedgerState): LedgerState {
        return synchronized(lock) {
            invalidateCaches()
            repositoryLocked().update(transform)
        }
    }

    private fun invalidateCaches() {
        screenCache = null
        reviewCache = null
        reviewCacheKey = null
    }

    private fun flushLocked() {
        val messages = pendingMessages.toList()
        val sources = pendingSources.toList()
        if (messages.isEmpty() && sources.isEmpty()) return
        if (messages.isNotEmpty()) writeLocked(messages)
        sources.forEach { stream(it) {} }
        pendingMessages.clear()
        pendingSources.clear()
    }

    private fun stream(source: SmsSource, onPage: (IngestTally) -> Unit) {
        var tally = IngestTally()
        var working = synchronized(lock) { repositoryLocked().loadWorkingSet() }
        source.forEachPage(SmsPages.DEFAULT_PAGE_SIZE) { page ->
            if (page.isEmpty()) return@forEachPage
            val before = working
            var cursor = working
            var pageTally = IngestTally()
            for (sms in page) {
                val result = pipeline.ingest(sms, cursor)
                cursor = result.state
                pageTally = pageTally.add(result)
            }
            synchronized(lock) {
                val repository = repositoryLocked()
                repository.append(before, cursor)
                repository.rememberSenders(pipeline.rememberedInstitutions())
                invalidateCaches()
            }
            working = cursor.withoutMessageBodies()
            tally += pageTally
            onPage(tally)
        }
    }

    private fun writeLocked(messages: List<InboundSms>) {
        if (messages.isEmpty()) return
        val repository = repositoryLocked()
        repository.update { state ->
            pipeline.ingestAll(SmsSource { messages }, state)
        }
        repository.rememberSenders(pipeline.rememberedInstitutions())
        invalidateCaches()
    }

    private fun repositoryLocked(): SqlDelightLedgerRepository {
        repository?.let { return it }
        val key = passphrase ?: throw DatabaseLockedException()
        val driver = openDriver(databaseFile, key.copyOf())
        val database = ExpenseDatabase(driver)
        val created = SqlDelightLedgerRepository(database)
        created.ensureSeed()
        pipeline.preloadInstitutions(created.learnedSenders())
        repository = created
        return created
    }

    /** Provider date and id of the newest stored inbox row, when one exists. */
    fun inboxCursor(): Pair<Long, Long>? = synchronized(lock) {
        if (passphrase == null) return null
        repositoryLocked().inboxHighWater()
    }

    /** Links posted transactions whose SMS states a card or account. Returns how many changed. */
    fun relinkStoredAccounts(pageSize: Int = SmsPages.DEFAULT_PAGE_SIZE): Int {
        return synchronized(lock) {
            if (passphrase == null) return 0
            val linked = repositoryLocked().relinkUnassigned(
                pageSize = pageSize,
                identify = pipeline::explicitAccount,
                newAccountId = ids::newId,
            )
            if (linked > 0) invalidateCaches()
            linked
        }
    }
}

internal fun LedgerState.withoutMessageBodies(): LedgerState {
    if (messages.none { !it.body.isNullOrEmpty() }) return this
    return copy(
        messages = messages.map { message ->
            if (message.body.isNullOrEmpty()) message else message.copy(body = "")
        },
    )
}
