package expense.android.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import expense.android.storage.db.ExpenseDatabase
import expense.categories.Category
import expense.categories.CategoryRule
import expense.categories.CategorySource
import expense.categories.MatchType
import expense.categories.NewCategory
import expense.categories.RuleSource
import expense.ingest.IngestPipeline
import expense.ingest.PipelineMetadata
import expense.ledger.Account
import expense.ledger.Correction
import expense.ledger.CorrectionField
import expense.ledger.EvidenceRole
import expense.ledger.LedgerState
import expense.ledger.OccurredSource
import expense.ledger.PossibleDuplicate
import expense.ledger.ReviewDismissal
import expense.ledger.StoredSms
import expense.ledger.Transaction
import expense.ledger.TransactionEvidence
import expense.ledger.TransactionStatus
import expense.merchants.AliasSource
import expense.merchants.AliasType
import expense.merchants.Merchant
import expense.merchants.MerchantAlias
import expense.money.Currency
import expense.money.Money
import expense.parse.AccountKind
import expense.parse.BankProfile
import expense.parse.BankRegistry
import expense.parse.Direction
import expense.parse.Extraction
import expense.parse.ParseAttempt
import expense.parse.ParseStatus
import expense.parse.TransactionCandidate
import expense.parse.TransactionKind
import expense.sms.BodyHash
import expense.sms.InboundSms
import expense.sms.SmsPages
import expense.sms.SmsSource
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

class LedgerRepositoryTest {
    @Test
    fun `round trip keeps bodies accounts categories corrections and review state`() {
        val repository = memoryRepository()
        val body = "Charged EGP 20.00 at Shop"
        val receivedAt = Instant.parse("2026-05-01T07:00:00Z")
        val occurred = LocalDateTime.of(2026, 5, 1, 10, 0)
        val state = LedgerState(
            messages = listOf(
                StoredSms("sms-1", "LAB", body, BodyHash.sha256(body), "11", receivedAt),
                StoredSms("sms-2", "NEWS", null, BodyHash.sha256("hello"), null, receivedAt.plusSeconds(60)),
            ),
            attempts = listOf(
                ParseAttempt(
                    id = "attempt-1",
                    smsId = "sms-1",
                    pipelineVersion = "1",
                    profileId = null,
                    profileVersion = null,
                    templateId = null,
                    status = ParseStatus.UNSUPPORTED,
                    confidence = 40,
                    extraction = Extraction(
                        confidence = 40,
                        candidates = listOf(
                            TransactionCandidate(
                                kind = TransactionKind.PURCHASE,
                                amount = Money(2000, Currency.EGP),
                                direction = Direction.DEBIT,
                                merchantRaw = "Shop",
                                occurredAt = occurred,
                                reference = "REF-9",
                                accountMask = "4242",
                                accountKind = AccountKind.DEBIT_CARD,
                                balance = Money(5000, Currency.EGP),
                                foreignAmount = Money(100, Currency.USD),
                            ),
                        ),
                    ),
                    error = null,
                ),
            ),
            accounts = listOf(
                Account("acct-1", "bank-1", AccountKind.DEBIT_CARD, "4242", Currency.EGP, "Daily"),
            ),
            transactions = listOf(sampleTransaction(categoryId = "groceries")),
            evidence = listOf(TransactionEvidence("ev-1", "tx-1", "sms-1", EvidenceRole.PRIMARY)),
            merchants = listOf(Merchant("merchant-1", "Cafe Roma", "cafe roma")),
            aliases = listOf(
                MerchantAlias("alias-1", "merchant-1", AliasType.EXACT_RAW, "Cafe Roma", 10, AliasSource.USER),
            ),
            categoryRules = listOf(
                CategoryRule("rule-1", MatchType.MERCHANT, "merchant-1", "groceries", 10, RuleSource.USER),
            ),
            corrections = listOf(
                Correction(
                    id = "corr-1",
                    dedupKey = "dedup-1",
                    field = CorrectionField.CATEGORY,
                    previousValue = null,
                    updatedValue = "groceries",
                    applyForward = true,
                    createdAt = receivedAt,
                ),
            ),
            possibleDuplicates = listOf(PossibleDuplicate("dup-1", "tx-1", "tx-2")),
            categories = listOf(Category("pets", "shopping", "Pets", "حيوانات", "pets", 61, system = false)),
            reviewDismissals = listOf(
                ReviewDismissal("LAB", BodyHash.sha256(body), receivedAt, "11"),
            ),
        )
        repository.save(state)
        val loaded = repository.load()
        assertEquals(state.messages, loaded.messages)
        assertEquals(state.attempts, loaded.attempts)
        assertEquals(state.accounts, loaded.accounts)
        assertEquals(state.transactions, loaded.transactions)
        assertEquals(state.evidence, loaded.evidence)
        assertEquals(state.merchants, loaded.merchants)
        assertEquals(state.aliases, loaded.aliases)
        assertEquals(state.categoryRules, loaded.categoryRules)
        assertEquals(state.corrections, loaded.corrections)
        assertEquals(state.possibleDuplicates, loaded.possibleDuplicates)
        assertEquals(state.categories, loaded.categories)
        assertEquals(state.reviewDismissals, loaded.reviewDismissals)
        assertTrue(loaded.categories.none { it.id == "food" })
    }

    @Test
    fun `search covers body merchant reference amount and category without wildcards`() {
        val repository = memoryRepository()
        val receivedAt = Instant.parse("2026-05-01T07:00:00Z")
        val purchase = sampleTransaction(categoryId = "groceries").copy(
            merchantRaw = "Cafe Roma",
            reference = "REF-9",
            amount = Money(15000, Currency.EGP),
            smsId = "sms-1",
        )
        repository.save(
            LedgerState(
                messages = listOf(
                    StoredSms("sms-1", "LAB", "Weekly pineapple note", BodyHash.sha256("Weekly pineapple note"), "11", receivedAt),
                    StoredSms("sms-2", "LAB", "unrelated hello", BodyHash.sha256("unrelated hello"), "12", receivedAt),
                ),
                transactions = listOf(purchase),
                merchants = listOf(Merchant("merchant-1", "Cafe Roma", "cafe roma")),
                categories = listOf(Category("pets", "food", "Pets", "حيوانات", "pets", 61, system = false)),
            ),
        )
        assertEquals(setOf(SearchField.BODY), repository.search("pineapple").single().fields)
        assertEquals(setOf(SearchField.MERCHANT), repository.search("cafe roma").single().fields)
        assertEquals(setOf(SearchField.REFERENCE), repository.search("REF-9").single().fields)
        assertTrue(repository.search("150.00").single().fields.contains(SearchField.AMOUNT))
        assertTrue(repository.search("15000").single().fields.contains(SearchField.AMOUNT))
        assertEquals(setOf(SearchField.CATEGORY), repository.search("Groceries").single().fields)
        assertEquals(setOf(SearchField.CATEGORY), repository.search("Food").single().fields)
        assertEquals(setOf(SearchField.CATEGORY), repository.search("بقالة").single().fields)
        assertEquals("sms-2", repository.search("unrelated").single().smsId)
        assertTrue(repository.search("%").isEmpty())
        assertTrue(repository.search("   ").isEmpty())
    }

    @Test
    fun `search returns every historical match newest first and a page does not load the rest`() {
        val repository = memoryRepository()
        val older = (0 until 24).map { index ->
            val body = "talabat purchase $index"
            StoredSms(
                id = "old-$index",
                sender = "LAB",
                body = body,
                bodyHash = BodyHash.sha256(body),
                providerMessageId = "p$index",
                receivedAt = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(index.toLong()),
            )
        }
        val recentBody = "x".repeat(220) + "talabat later"
        val recent = StoredSms(
            id = "new-1",
            sender = "LAB",
            body = recentBody,
            bodyHash = BodyHash.sha256(recentBody),
            providerMessageId = "p-new",
            receivedAt = Instant.parse("2026-06-01T00:00:00Z"),
        )
        repository.save(LedgerState(messages = older + recent))
        val hits = repository.search("talabat")
        assertEquals(25, hits.size)
        assertEquals("new-1", hits.first().smsId)
        assertEquals("old-0", hits.last().smsId)
        assertTrue(hits.first().sortAt > hits.last().sortAt)
        val page = hits.take(20)
        val snapshot = repository.snapshot(page)
        assertEquals(page.mapNotNull { it.smsId }.toSet(), snapshot.messages.map { it.id }.toSet())
        assertTrue(snapshot.messages.none { it.id == "old-0" })
        assertTrue(snapshot.messages.single { it.id == "new-1" }.body.orEmpty().contains("talabat later"))
    }

    @Test
    fun `notices stored under the old gate leave review one page at a time`() {
        val repository = memoryRepository()
        val received = Instant.parse("2026-05-01T00:00:00Z")
        fun sms(id: String, body: String, seconds: Long) = StoredSms(
            id,
            "LAB",
            body,
            BodyHash.sha256(body),
            id,
            received.plusSeconds(seconds),
        )
        fun attempt(id: String, smsId: String, status: ParseStatus) = ParseAttempt(
            id = id,
            smsId = smsId,
            pipelineVersion = "1",
            profileId = null,
            profileVersion = null,
            templateId = null,
            status = status,
            confidence = null,
            extraction = null,
            error = null,
        )
        val promo = "Save EGP 50 this weekend. Use code 20"
        val otp = "Your OTP is 482193"
        val balance = "Your available balance is EGP 1,250.00"
        val charged = "Charged EGP 20.00 at Shop on card ****4242"
        val parsed = "TB|purchase|EGP|10.00|Shop|4242|S1|15/01/2026 10:00"
        repository.save(
            LedgerState(
                messages = listOf(
                    sms("promo", promo, 0),
                    sms("otp", otp, 1),
                    sms("balance", balance, 2),
                    sms("charged", charged, 3),
                    sms("parsed", parsed, 4),
                    sms("orphan", promo, 5),
                ),
                attempts = listOf(
                    attempt("a-promo", "promo", ParseStatus.UNSUPPORTED),
                    attempt("a-otp", "otp", ParseStatus.UNSUPPORTED),
                    attempt("a-balance", "balance", ParseStatus.UNSUPPORTED),
                    attempt("a-charged", "charged", ParseStatus.UNSUPPORTED),
                    attempt("a-parsed", "parsed", ParseStatus.PARSED),
                ),
            ),
        )
        assertEquals(1, reclassify(repository, pageSize = 1, maxPages = 1))
        val firstPage = repository.load()
        assertEquals(null, firstPage.messages.single { it.id == "promo" }.body)
        assertEquals(ParseStatus.IGNORED_NOT_BANK, firstPage.attempts.single { it.smsId == "promo" }.status)
        assertEquals(PipelineMetadata.VERSION, firstPage.attempts.single { it.smsId == "promo" }.pipelineVersion)
        assertEquals(otp, firstPage.messages.single { it.id == "otp" }.body)
        assertEquals("1", firstPage.attempts.single { it.smsId == "otp" }.pipelineVersion)
        assertEquals(4, reclassify(repository, pageSize = 1))
        val loaded = repository.load()
        assertEquals(null, loaded.messages.single { it.id == "promo" }.body)
        assertEquals(null, loaded.messages.single { it.id == "otp" }.body)
        assertEquals(null, loaded.messages.single { it.id == "balance" }.body)
        assertEquals(null, loaded.messages.single { it.id == "orphan" }.body)
        assertEquals(charged, loaded.messages.single { it.id == "charged" }.body)
        assertEquals(parsed, loaded.messages.single { it.id == "parsed" }.body)
        assertEquals(ParseStatus.IGNORED_NOT_BANK, loaded.attempts.single { it.smsId == "promo" }.status)
        assertEquals(ParseStatus.PARSED, loaded.attempts.single { it.smsId == "charged" }.status)
        assertEquals(PipelineMetadata.VERSION, loaded.attempts.single { it.smsId == "charged" }.pipelineVersion)
        assertEquals(1, loaded.transactions.size)
        assertEquals(ParseStatus.PARSED, loaded.attempts.single { it.smsId == "parsed" }.status)
        assertEquals("1", loaded.attempts.single { it.smsId == "parsed" }.pipelineVersion)
        assertEquals(0, repository.reviewWindow(0, 20).total)
        assertEquals(2, repository.storedTally().financial)
        assertEquals(0, repository.storedTally().unsupported)
        assertEquals(0, reclassify(repository, pageSize = 1))
        assertEquals(loaded.attempts.map { it.status }, repository.load().attempts.map { it.status })
    }

    @Test
    fun `a stored CIB transfer is posted when the sender is verified`() {
        val repository = memoryRepository()
        val body = "Your account ending with ******9438 is debited with amount EGP 31.89DR on 31 MAR 2024 with transfer to another account."
        val received = Instant.parse("2026-05-02T00:00:00Z")
        repository.save(
            LedgerState(
                messages = listOf(StoredSms("cib", "CIB", body, BodyHash.sha256(body), "cib", received)),
                attempts = listOf(
                    ParseAttempt(
                        id = "a-cib",
                        smsId = "cib",
                        pipelineVersion = "1",
                        profileId = null,
                        profileVersion = null,
                        templateId = null,
                        status = ParseStatus.UNSUPPORTED,
                        confidence = null,
                        extraction = null,
                        error = null,
                    ),
                ),
            ),
        )
        assertEquals(1, reclassify(repository, pageSize = 1))
        val loaded = repository.load()
        val attempt = loaded.attempts.single()
        assertEquals(ParseStatus.PARSED, attempt.status)
        assertEquals(PipelineMetadata.VERSION, attempt.pipelineVersion)
        assertEquals(body, loaded.messages.single().body)
        assertEquals(TransactionKind.TRANSFER_OUT, loaded.transactions.single().kind)
        assertEquals("cib", loaded.transactions.single().institutionId)
        assertEquals("9438", loaded.accounts.single().mask)
        assertEquals(0, repository.reviewWindow(0, 20).total)
        assertEquals(0, reclassify(repository, pageSize = 1))
    }

    @Test
    fun `a stored charge from a verified sender is posted by the current pipeline`() {
        val repository = memoryRepository()
        val body = "Charged EGP 20.00 at Shop"
        val received = Instant.parse("2026-05-03T00:00:00Z")
        repository.save(
            LedgerState(
                messages = listOf(StoredSms("shop", "VERIFIED", body, BodyHash.sha256(body), "shop", received)),
                attempts = listOf(
                    ParseAttempt(
                        id = "a-shop",
                        smsId = "shop",
                        pipelineVersion = "1",
                        profileId = null,
                        profileVersion = null,
                        templateId = null,
                        status = ParseStatus.UNSUPPORTED,
                        confidence = null,
                        extraction = null,
                        error = null,
                    ),
                ),
            ),
        )
        val pipeline = IngestPipeline(
            registry = BankRegistry(
                listOf(
                    BankProfile(
                        id = "example.verified",
                        version = "1",
                        displayName = "Verified Example",
                        senderIds = setOf("VERIFIED"),
                        templates = emptyList(),
                    ),
                ),
            ),
        )
        assertEquals(1, reclassify(repository, pipeline, pageSize = 1))
        val loaded = repository.load()
        assertEquals(ParseStatus.PARSED, loaded.attempts.single().status)
        assertEquals(PipelineMetadata.VERSION, loaded.attempts.single().pipelineVersion)
        assertEquals(body, loaded.messages.single().body)
        assertEquals(1, loaded.transactions.size)
        assertEquals(TransactionKind.PURCHASE, loaded.transactions.single().kind)
        assertEquals(0, repository.reviewWindow(0, 20).total)
        assertEquals(0, reclassify(repository, pipeline, pageSize = 1))
    }

    @Test
    fun `relink attaches only the account the sms states`() {
        val repository = memoryRepository()
        val maskedBody = "Your credit card ****4229 was charged EGP 41.76 at Uber"
        val bareBody = "Charged EGP 5.00 at Uber"
        val received = Instant.parse("2026-09-28T17:58:00Z")
        repository.save(
            LedgerState(
                messages = listOf(
                    StoredSms("masked", "CIB", maskedBody, BodyHash.sha256(maskedBody), "1", received),
                    StoredSms("bare", "CIB", bareBody, BodyHash.sha256(bareBody), "2", received.plusSeconds(60)),
                ),
                transactions = listOf(
                    sampleTransaction(null).copy(
                        id = "tx-mask",
                        dedupKey = "d1",
                        smsId = "masked",
                        accountId = null,
                        institutionId = "cib",
                        merchantId = null,
                    ),
                    sampleTransaction(null).copy(
                        id = "tx-bare",
                        dedupKey = "d2",
                        smsId = "bare",
                        accountId = null,
                        institutionId = "cib",
                        merchantId = null,
                    ),
                ),
            ),
        )
        assertEquals(received.plusSeconds(60).toEpochMilli() to 2L, repository.inboxHighWater())
        val pipeline = IngestPipeline()
        assertEquals(
            1,
            repository.relinkUnassigned(10, pipeline::explicitAccount) { "acct-new" },
        )
        val loaded = repository.load()
        assertEquals("acct-new", loaded.transactions.single { it.id == "tx-mask" }.accountId)
        assertEquals(null, loaded.transactions.single { it.id == "tx-bare" }.accountId)
        assertEquals("4229", loaded.accounts.single().mask)
        assertEquals(AccountKind.CREDIT_CARD, loaded.accounts.single().kind)
        assertEquals(0, repository.relinkUnassigned(10, pipeline::explicitAccount) { "acct-again" })
    }

    @Test
    fun `the android session is wired to the verified bank catalog`() {
        val source = File("src/main/kotlin/expense/android/storage/LedgerSessions.kt").readText()
        assertTrue(source.contains("VerifiedBankCatalog"))
    }

    private fun sampleTransaction(categoryId: String?): Transaction {
        return Transaction(
            id = "tx-1",
            dedupKey = "dedup-1",
            smsId = "sms-1",
            institutionId = "bank-1",
            accountId = "acct-1",
            kind = TransactionKind.PURCHASE,
            status = TransactionStatus.POSTED,
            amount = Money(15000, Currency.EGP),
            direction = Direction.DEBIT,
            occurredAt = Instant.parse("2026-05-01T08:00:00Z"),
            occurredCivil = LocalDateTime.of(2026, 5, 1, 10, 0),
            occurredSource = OccurredSource.SMS_FIELD,
            merchantRaw = "Cafe Roma",
            merchantId = "merchant-1",
            categoryId = categoryId,
            categorySource = CategorySource.USER,
            reference = "REF-9",
            balance = Money(5000, Currency.EGP),
            foreignAmount = Money(100, Currency.USD),
            duplicateOfId = null,
            linkedTransactionId = null,
            includeInSpend = true,
            pipelineVersion = "1",
            profileVersion = "",
            installmentIndex = null,
            installmentCount = null,
            manual = false,
        )
    }
}

class DatabaseRetentionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `failed unlock keeps the existing database bytes`() {
        val databaseFile = directory.resolve("expense.db").toFile()
        val original = byteArrayOf(9, 8, 7, 6, 5)
        databaseFile.writeBytes(original)
        val opened = AtomicInteger()
        val session = session(databaseFile, ScriptedVault(KeyMaterial.Unavailable), opened)
        val result = unlock(session) { onSuccess, _ -> onSuccess() }
        assertEquals(UnlockResult.KeyUnavailable, result)
        assertArrayEquals(original, databaseFile.readBytes())
        assertEquals(0, opened.get())
        assertThrows(DatabaseLockedException::class.java) { session.load() }
        assertFalse(databaseFile.readBytes().isEmpty())
    }

    @Test
    fun `keystore refusal does not delete the database or the wrapped key`() {
        val databaseFile = directory.resolve("expense.db").toFile()
        val wrapFile = directory.resolve("expense.db.wrap").toFile()
        val original = byteArrayOf(1, 2, 3, 4)
        val wrapped = byteArrayOf(5, 6, 7)
        databaseFile.writeBytes(original)
        wrapFile.writeBytes(wrapped)
        val box = MemoryBox(alias = true, invalid = true)
        val vault = WrappedDatabaseKey(databaseFile, wrapFile, box) { byteArrayOf(1) }
        assertEquals(KeyMaterial.Unavailable, vault.readOrCreate(databaseExists = true))
        assertEquals(0, box.deleted)
        assertArrayEquals(original, databaseFile.readBytes())
        assertArrayEquals(wrapped, wrapFile.readBytes())
    }

    @Test
    fun `creating a key without a database does not invent a database file`() {
        val databaseFile = directory.resolve("missing.db").toFile()
        val wrapFile = directory.resolve("missing.db.wrap").toFile()
        val box = MemoryBox(alias = false, invalid = false)
        val vault = WrappedDatabaseKey(databaseFile, wrapFile, box) { byteArrayOf(4, 5, 6) }
        val material = vault.readOrCreate(databaseExists = false)
        assertTrue(material is KeyMaterial.Available)
        assertEquals(1, box.generated)
        assertTrue(wrapFile.exists())
        assertFalse(databaseFile.exists())
    }

    @Test
    fun `cold start prompts once and then persists queued financial sms`() {
        val databaseFile = directory.resolve("ledger.db").toFile()
        val opened = AtomicInteger()
        var prompts = 0
        val session = session(databaseFile, ScriptedVault(KeyMaterial.Available(byteArrayOf(1, 2, 3))), opened)
        val financial = InboundSms("01005551234", "Debited EGP 20.00 for Shop", "11", Instant.parse("2026-05-01T07:00:00Z"))
        val ignored = InboundSms("NEWS", "hello there", "12", Instant.parse("2026-05-01T08:00:00Z"))
        session.accept(listOf(financial))
        assertEquals(0, opened.get())
        val prompt = UnlockPrompt { onSuccess, _ ->
            prompts++
            onSuccess()
        }
        assertEquals(UnlockResult.Ready, unlock(session, prompt))
        session.accept(listOf(ignored))
        assertEquals(UnlockResult.Ready, unlock(session, prompt))
        assertEquals(1, prompts)
        assertEquals(1, opened.get())
        val state = session.load()
        assertEquals("Debited EGP 20.00 for Shop", state.messages.first { it.providerMessageId == "11" }.body)
        assertEquals(null, state.messages.first { it.providerMessageId == "12" }.body)
        assertEquals(1, state.reviewQueue().size)
        val renamed = session.addCategory(NewCategory("pets", null, "Pets", "حيوانات", "pets", 200))
        assertEquals("pets", renamed.categories.single().id)
        session.dismissReview(state.reviewQueue().single().id)
        assertTrue(session.load().reviewQueue().isEmpty())
        assertEquals("Debited EGP 20.00 for Shop", session.load().messages.first { it.providerMessageId == "11" }.body)
        val hits = session.search("Shop")
        assertTrue(hits.any { SearchField.BODY in it.fields })
    }

    @Test
    fun `a large inbox is ingested one page at a time`() {
        val databaseFile = directory.resolve("inbox.db").toFile()
        val opened = AtomicInteger()
        val session = session(databaseFile, ScriptedVault(KeyMaterial.Available(byteArrayOf(1))), opened)
        assertEquals(UnlockResult.Ready, unlock(session))
        val total = 95
        var fullListRequested = false
        val source = object : SmsSource {
            override fun messages(): List<InboundSms> {
                fullListRequested = true
                error("the inbox must not be loaded as one list")
            }

            override fun forEachPage(pageSize: Int, accept: (List<InboundSms>) -> Unit) {
                assertEquals(SmsPages.DEFAULT_PAGE_SIZE, pageSize)
                val items = (0 until total).map { index ->
                    InboundSms("NEWS", "hello $index", index.toString(), Instant.EPOCH.plusMillis(index.toLong()))
                }
                var stored = 0
                SmsPages.consume(items.iterator(), pageSize) { page ->
                    assertTrue(page.size <= pageSize)
                    accept(page)
                    stored += page.size
                    assertEquals(stored, session.load().messages.size)
                }
            }
        }
        session.ingest(source)
        assertFalse(fullListRequested)
        assertEquals(total, session.load().messages.size)
        assertTrue(session.load().messages.all { it.body == null })
    }

    @Test
    fun `a paged scan keeps financial bodies in the database and review shows one page`() {
        val databaseFile = directory.resolve("financial.db").toFile()
        val opened = AtomicInteger()
        val session = session(databaseFile, ScriptedVault(KeyMaterial.Available(byteArrayOf(1))), opened)
        assertEquals(UnlockResult.Ready, unlock(session))
        val total = 45
        val source = object : SmsSource {
            override fun messages(): List<InboundSms> = error("the inbox must not be loaded as one list")

            override fun forEachPage(pageSize: Int, accept: (List<InboundSms>) -> Unit) {
                val items = (0 until total).map { index ->
                    InboundSms(
                        sender = "01005551234",
                        body = "Debited EGP $index for Shop",
                        providerMessageId = index.toString(),
                        receivedAt = Instant.EPOCH.plusSeconds(index.toLong()),
                    )
                } + InboundSms("NEWS", "hello there", "chatter", Instant.EPOCH.plusSeconds(1000))
                SmsPages.consume(items.iterator(), pageSize, accept)
            }
        }
        var pages = 0
        session.ingest(source) { pages += 1 }
        val loaded = session.load()
        assertEquals(total + 1, loaded.messages.size)
        assertEquals(
            (0 until total).map { "Debited EGP $it for Shop" },
            loaded.messages.filter { it.body != null }.map { it.body },
        )
        assertTrue(loaded.transactions.isEmpty())
        assertTrue(pages >= 2)
        val first = session.reviewWindow(0, 20)
        assertEquals(20, first.rows.size)
        assertEquals(total, first.total)
        assertEquals("Debited EGP 0 for Shop", first.rows.first().body)
        val second = session.reviewWindow(40, 20)
        assertEquals(5, second.rows.size)
        assertEquals("Debited EGP 44 for Shop", second.rows.last().body)
        assertTrue(second.rows.none { it.body == "Debited EGP 0 for Shop" })
        val tally = session.storedTally()
        assertEquals(total + 1, tally.scanned)
        assertEquals(total, tally.financial)
        assertEquals(0, tally.matchedProfile)
        assertEquals(total, tally.unsupported)
        assertEquals(0, tally.parsed)
        assertEquals(0, tally.posted)
    }

    @Test
    fun `authentication failure does not open or replace the file`() {
        val databaseFile = directory.resolve("expense.db").toFile()
        val original = byteArrayOf(3, 3, 3)
        databaseFile.writeBytes(original)
        val opened = AtomicInteger()
        val vault = ScriptedVault(KeyMaterial.Available(byteArrayOf(9)))
        val session = session(databaseFile, vault, opened)
        val result = unlock(session) { _, onFailure -> onFailure() }
        assertEquals(UnlockResult.AuthenticationFailed, result)
        assertEquals(0, vault.calls)
        assertEquals(0, opened.get())
        assertArrayEquals(original, databaseFile.readBytes())
    }

    private fun session(file: File, vault: DatabaseKeyVault, opened: AtomicInteger): LedgerSession {
        return LedgerSession(
            databaseFile = file,
            vault = vault,
            openDriver = { database, _ ->
                opened.incrementAndGet()
                jdbc(database)
            },
        )
    }

    private fun unlock(session: LedgerSession, prompt: UnlockPrompt = UnlockPrompt { onSuccess, _ -> onSuccess() }): UnlockResult {
        var result: UnlockResult = UnlockResult.AuthenticationFailed
        session.unlock(prompt) { result = it }
        return result
    }
}

class KeystorePolicyTest {
    @Test
    fun `database key survives biometric reenrollment and expires only for the unwrap window`() {
        val policy = KeystoreKeyPolicy.DATABASE
        assertTrue(policy.userAuthenticationRequired)
        assertFalse(policy.invalidatedByBiometricEnrollment)
        assertTrue(policy.authenticationValiditySeconds > 0)
        assertTrue(policy.biometricAllowed)
        assertTrue(policy.deviceCredentialAllowed)
        assertTrue(policy.unlockedDeviceRequired)
        assertEquals(0, keystoreAuthenticationTimeoutSeconds(35, policy))
        assertEquals(60, keystoreAuthenticationTimeoutSeconds(28, policy))
        assertTrue(bindsKeystoreCipher(35))
        assertFalse(bindsKeystoreCipher(28))
        assertTrue(isUserAuthenticationFailure(RuntimeException("User not authenticated")))
        assertTrue(
            isUserAuthenticationFailure(
                RuntimeException("Keystore operation failed", RuntimeException("User authentication required")),
            ),
        )
        assertFalse(isUserAuthenticationFailure(RuntimeException("database is corrupt")))
        assertTrue(unlockedDeviceRequirementRejected(RuntimeException("Device must be unlocked")))
        assertTrue(unlockedDeviceRequirementRejected(RuntimeException("-66")))
        assertFalse(unlockedDeviceRequirementRejected(RuntimeException("User not authenticated")))
        assertEquals(KeyDecision.REFUSE, keyDecision(true, keystorePresent = false, keystoreInvalid = false, wrapPresent = true))
        assertEquals(KeyDecision.REFUSE, keyDecision(true, keystorePresent = true, keystoreInvalid = true, wrapPresent = true))
        assertEquals(KeyDecision.UNWRAP, keyDecision(true, keystorePresent = true, keystoreInvalid = false, wrapPresent = true))
        assertEquals(KeyDecision.CREATE, keyDecision(false, keystorePresent = false, keystoreInvalid = false, wrapPresent = false))
    }
}

class KeystoreUnlockCrashTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `fingerprint success that cannot use the keystore key does not crash`() {
        val databaseFile = directory.resolve("expense.db").toFile()
        val original = byteArrayOf(4, 4, 4, 4)
        databaseFile.writeBytes(original)
        val opened = AtomicInteger()
        val session = LedgerSession(
            databaseFile = databaseFile,
            vault = DatabaseKeyVault { throw UserAuthRequiredException() },
            openDriver = { _, _ ->
                opened.incrementAndGet()
                error("driver must not open")
            },
        )
        val result = unlock(session) { onSuccess, _ -> onSuccess() }
        assertEquals(UnlockResult.AuthenticationFailed, result)
        assertEquals(0, opened.get())
        assertArrayEquals(original, databaseFile.readBytes())
        assertFalse(session.isUnlocked())
    }

    @Test
    fun `creating a key reports auth failure instead of throwing and does not create a database`() {
        val databaseFile = directory.resolve("expense.db").toFile()
        val wrapFile = directory.resolve("expense.db.wrap").toFile()
        val box = MemoryBox(alias = false, invalid = false).apply {
            encryptFailure = UserAuthRequiredException()
        }
        val vault = WrappedDatabaseKey(databaseFile, wrapFile, box) { byteArrayOf(9, 9) }
        assertEquals(KeyMaterial.AuthenticationRequired, vault.readOrCreate(databaseExists = false))
        assertFalse(databaseFile.exists())
        assertFalse(wrapFile.exists())
    }

    @Test
    fun `authorized cipher wraps the passphrase without the unauthenticated encrypt path`() {
        val databaseFile = directory.resolve("expense.db").toFile()
        val wrapFile = directory.resolve("expense.db.wrap").toFile()
        val opened = AtomicInteger()
        var unauthenticatedEncrypts = 0
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, key) }
        val box = object : SecretKeyBox {
            override fun containsAlias(): Boolean = false
            override fun deleteAlias() = Unit
            override fun generate() = Unit
            override fun encrypt(plaintext: ByteArray): ByteArray {
                unauthenticatedEncrypts++
                throw UserAuthRequiredException()
            }
            override fun decrypt(wrapped: ByteArray): ByteArray = error("decrypt is not used")
            override fun openEncrypt(plaintext: ByteArray): BoxOperation {
                val copy = plaintext.copyOf()
                return BoxOperation.Authorize(cipher) { authed ->
                    val payload = authed.doFinal(copy)
                    authed.iv + payload
                }
            }
        }
        val session = LedgerSession(
            databaseFile = databaseFile,
            vault = WrappedDatabaseKey(databaseFile, wrapFile, box) { byteArrayOf(7, 8, 9) },
            openDriver = { file, _ ->
                opened.incrementAndGet()
                jdbc(file)
            },
        )
        val result = unlock(session, ImmediateCipherPrompt(succeed = true))
        assertEquals(UnlockResult.Ready, result)
        assertEquals(0, unauthenticatedEncrypts)
        assertTrue(session.isUnlocked())
        assertTrue(wrapFile.length() > 12)
        assertFalse(databaseFile.exists())
        assertEquals(0, opened.get())
    }

    @Test
    fun `cancelled cipher prompt leaves an existing database and wrap untouched`() {
        val databaseFile = directory.resolve("expense.db").toFile()
        val wrapFile = directory.resolve("expense.db.wrap").toFile()
        val original = byteArrayOf(1, 2, 3)
        val wrapped = byteArrayOf(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 9)
        databaseFile.writeBytes(original)
        wrapFile.writeBytes(wrapped)
        var finished = 0
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(Cipher.ENCRYPT_MODE, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        }
        val box = object : SecretKeyBox {
            override fun containsAlias(): Boolean = true
            override fun deleteAlias() = error("alias must stay")
            override fun generate() = error("key must stay")
            override fun encrypt(plaintext: ByteArray): ByteArray = error("encrypt must not run")
            override fun decrypt(wrappedBytes: ByteArray): ByteArray = error("decrypt must not run")
            override fun openDecrypt(wrappedBytes: ByteArray): BoxOperation {
                return BoxOperation.Authorize(cipher) {
                    finished++
                    throw UserAuthRequiredException()
                }
            }
        }
        val session = LedgerSession(
            databaseFile = databaseFile,
            vault = WrappedDatabaseKey(databaseFile, wrapFile, box),
            openDriver = { _, _ -> error("driver must not open") },
        )
        assertEquals(UnlockResult.AuthenticationFailed, unlock(session, ImmediateCipherPrompt(succeed = false)))
        assertEquals(0, finished)
        assertArrayEquals(original, databaseFile.readBytes())
        assertArrayEquals(wrapped, wrapFile.readBytes())
        assertFalse(session.isUnlocked())
    }

    @Test
    fun `authorized unwrap that still requires auth does not replace the database`() {
        val databaseFile = directory.resolve("expense.db").toFile()
        val wrapFile = directory.resolve("expense.db.wrap").toFile()
        val original = byteArrayOf(3, 2, 1)
        val wrapped = byteArrayOf(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5)
        databaseFile.writeBytes(original)
        wrapFile.writeBytes(wrapped)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(Cipher.DECRYPT_MODE, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(), javax.crypto.spec.GCMParameterSpec(128, ByteArray(12)))
        }
        val box = object : SecretKeyBox {
            override fun containsAlias(): Boolean = true
            override fun deleteAlias() = error("alias must stay")
            override fun generate() = error("key must stay")
            override fun encrypt(plaintext: ByteArray): ByteArray = throw UserAuthRequiredException()
            override fun decrypt(wrappedBytes: ByteArray): ByteArray = throw UserAuthRequiredException()
            override fun openDecrypt(wrappedBytes: ByteArray): BoxOperation {
                return BoxOperation.Authorize(cipher) { throw UserAuthRequiredException() }
            }
        }
        val session = LedgerSession(
            databaseFile = databaseFile,
            vault = WrappedDatabaseKey(databaseFile, wrapFile, box),
            openDriver = { _, _ -> error("driver must not open") },
        )
        assertEquals(UnlockResult.AuthenticationFailed, unlock(session, ImmediateCipherPrompt(succeed = true)))
        assertArrayEquals(original, databaseFile.readBytes())
        assertArrayEquals(wrapped, wrapFile.readBytes())
        assertFalse(session.isUnlocked())
    }

    private fun unlock(session: LedgerSession, prompt: UnlockPrompt): UnlockResult {
        var result: UnlockResult = UnlockResult.KeyUnavailable
        session.unlock(prompt) { result = it }
        return result
    }
}

private class ImmediateCipherPrompt(
    private val succeed: Boolean,
) : UnlockPrompt {
    override fun bindsCipher(): Boolean = true

    override fun authorize(cipher: Cipher, onSuccess: (Cipher) -> Unit, onFailure: () -> Unit) {
        if (succeed) onSuccess(cipher) else onFailure()
    }

    override fun authenticate(onSuccess: () -> Unit, onFailure: () -> Unit) {
        if (succeed) onSuccess() else onFailure()
    }
}

private val migratedIds = AtomicInteger()

private fun reclassify(
    repository: SqlDelightLedgerRepository,
    pipeline: IngestPipeline = IngestPipeline(),
    pageSize: Int,
    maxPages: Int = Int.MAX_VALUE,
): Int {
    return repository.reclassifyRetained(
        pageSize = pageSize,
        maxPages = maxPages,
        newAttemptId = { "migrated-${migratedIds.incrementAndGet()}" },
        interpret = pipeline::interpretStored,
        post = { state, message, decision ->
            pipeline.postStored(state, message, checkNotNull(decision.profile), checkNotNull(decision.extraction))
        },
    )
}

private fun memoryRepository(): SqlDelightLedgerRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    ExpenseDatabase.Schema.create(driver)
    val repository = SqlDelightLedgerRepository(ExpenseDatabase(driver))
    repository.ensureSeed()
    return repository
}

private fun jdbc(file: File): SqlDriver {
    file.parentFile?.mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    ExpenseDatabase.Schema.create(driver)
    return driver
}

private class ScriptedVault(
    private val material: KeyMaterial,
) : DatabaseKeyVault {
    var calls: Int = 0

    override fun readOrCreate(databaseExists: Boolean): KeyMaterial {
        calls++
        return material
    }
}

private class MemoryBox(
    var alias: Boolean,
    var invalid: Boolean,
) : SecretKeyBox {
    var deleted: Int = 0
    var generated: Int = 0
    var encryptFailure: Exception? = null

    override fun containsAlias(): Boolean = alias

    override fun deleteAlias() {
        deleted++
        alias = false
    }

    override fun generate() {
        generated++
        alias = true
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        encryptFailure?.let { throw it }
        return plaintext.copyOf()
    }

    override fun decrypt(wrapped: ByteArray): ByteArray {
        if (invalid) throw KeyUnrecoverableException()
        return wrapped.copyOf()
    }
}
