package expense.android.storage

import expense.android.storage.db.ExpenseDatabase
import expense.android.storage.db.SelectRevisableTransactions
import expense.categories.Category
import expense.categories.CategoryCatalog
import expense.categories.CategoryRule
import expense.categories.CategorySeed
import expense.categories.CategorySource
import expense.categories.MatchType
import expense.categories.RuleSource
import expense.ledger.Account
import expense.ledger.Correction
import expense.ledger.CorrectionField
import expense.ledger.EvidenceRole
import expense.ledger.LedgerState
import expense.ledger.OccurredSource
import expense.ingest.CairoClock
import expense.ledger.PossibleDuplicate
import expense.ledger.ReviewDismissal
import expense.ledger.SpendPolicy
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
import expense.parse.Direction
import expense.ingest.PipelineMetadata
import expense.ingest.StoredInterpretation
import expense.intelligence.SenderEvidence
import expense.intelligence.senderInstitutionId
import expense.parse.Extraction
import expense.parse.ParseAttempt
import expense.parse.FinancialEventType
import expense.parse.ParseStatus
import expense.parse.SpendEffect
import expense.parse.TransactionCandidate
import java.time.Instant
import java.time.LocalDateTime

class SqlDelightLedgerRepository(
    private val database: ExpenseDatabase,
) {
    private companion object {
        const val SET_SEPARATOR: String = ","
    }

    fun ensureSeed() {
        database.transaction {
            CategorySeed.all.forEach { category -> insertCategory(category, system = true) }
        }
    }

    fun load(): LedgerState {
        val messages = database.expenseQueries.selectMessages().executeAsList().map { row ->
            StoredSms(
                id = row.id,
                sender = row.sender,
                body = row.body,
                bodyHash = row.bodyHash,
                providerMessageId = row.providerMessageId,
                receivedAt = Instant.ofEpochMilli(row.receivedAt),
            )
        }
        return read(messages)
    }

    fun loadWorkingSet(): LedgerState {
        val messages = database.expenseQueries.selectWorkingMessages().executeAsList().map { row ->
            StoredSms(
                id = row.id,
                sender = row.sender,
                body = if (row.retained == 0L) null else "",
                bodyHash = row.bodyHash,
                providerMessageId = row.providerMessageId,
                receivedAt = Instant.ofEpochMilli(row.receivedAt),
            )
        }
        return read(messages)
    }

    /**
     * Accounts, transactions, merchants, categories, and sender addresses.
     * SMS bodies, parse attempts, and extraction rows stay in the database.
     */
    fun screenProjection(): LedgerState {
        val messages = database.expenseQueries.selectWorkingMessages().executeAsList().map { row ->
            StoredSms(
                id = row.id,
                sender = row.sender,
                body = null,
                bodyHash = row.bodyHash,
                providerMessageId = row.providerMessageId,
                receivedAt = Instant.ofEpochMilli(row.receivedAt),
            )
        }
        return read(messages, history = false)
    }

    private fun read(messages: List<StoredSms>, history: Boolean = true): LedgerState {
        val queries = database.expenseQueries
        val candidates = if (history) {
            queries.selectCandidates().executeAsList().groupBy { it.attemptId }
        } else {
            emptyMap()
        }
        return LedgerState(
            messages = messages,
            attempts = if (!history) {
                emptyList()
            } else {
                queries.selectAttempts().executeAsList().map { row ->
                val rows = candidates[row.id].orEmpty()
                val extraction = row.confidence?.let { confidence ->
                    Extraction(
                        confidence = confidence.toInt(),
                        candidates = rows.map { candidate ->
                            TransactionCandidate(
                                eventType = FinancialEventType.valueOf(candidate.eventType),
                                spendEffect = SpendEffect.valueOf(candidate.spendEffect),
                                amount = money(candidate.amountMinor, candidate.amountCurrency),
                                direction = Direction.valueOf(candidate.direction),
                                merchantRaw = candidate.merchantRaw,
                                occurredAt = candidate.occurredAt?.let(LocalDateTime::parse),
                                reference = candidate.reference,
                                accountMask = candidate.accountMask,
                                accountKind = candidate.accountKind?.let(AccountKind::valueOf),
                                balance = money(candidate.balanceMinor, candidate.balanceCurrency),
                                foreignAmount = money(candidate.foreignMinor, candidate.foreignCurrency),
                                installmentIndex = candidate.installmentIndex?.toInt(),
                                installmentCount = candidate.installmentCount?.toInt(),
                            )
                        },
                    )
                }
                ParseAttempt(
                    id = row.id,
                    smsId = row.smsId,
                    pipelineVersion = row.pipelineVersion,
                    profileId = row.profileId,
                    profileVersion = row.profileVersion,
                    templateId = row.templateId,
                    status = ParseStatus.valueOf(row.status),
                    eventType = eventTypeOf(row.eventType),
                    confidence = row.confidence?.toInt(),
                    extraction = extraction,
                    error = row.error,
                )
                }
            },
            accounts = queries.selectAccounts().executeAsList().map { row ->
                Account(
                    id = row.id,
                    institutionId = row.institutionId,
                    kind = AccountKind.valueOf(row.kind),
                    mask = row.mask,
                    currency = Currency.of(row.currency),
                    displayName = row.displayName,
                )
            },
            transactions = queries.selectTransactions().executeAsList().map { row ->
                Transaction(
                    id = row.id,
                    dedupKey = row.dedupKey,
                    smsId = row.smsId,
                    institutionId = row.institutionId,
                    accountId = row.accountId,
                    eventType = FinancialEventType.valueOf(row.eventType),
                    spendEffect = SpendEffect.valueOf(row.spendEffect),
                    status = TransactionStatus.valueOf(row.status),
                    amount = Money(row.amountMinor, Currency.of(row.amountCurrency)),
                    direction = Direction.valueOf(row.direction),
                    occurredAt = Instant.ofEpochMilli(row.occurredAt),
                    occurredCivil = LocalDateTime.parse(row.occurredCivil),
                    occurredSource = OccurredSource.valueOf(row.occurredSource),
                    merchantRaw = row.merchantRaw,
                    merchantId = row.merchantId,
                    categoryId = row.categoryId,
                    categorySource = CategorySource.valueOf(row.categorySource),
                    reference = row.reference,
                    balance = money(row.balanceMinor, row.balanceCurrency),
                    foreignAmount = money(row.foreignMinor, row.foreignCurrency),
                    duplicateOfId = row.duplicateOfId,
                    linkedTransactionId = row.linkedTransactionId,
                    includeInSpend = row.includeInSpend == 1L,
                    pipelineVersion = row.pipelineVersion,
                    profileVersion = row.profileVersion,
                    installmentIndex = row.installmentIndex?.toInt(),
                    installmentCount = row.installmentCount?.toInt(),
                    manual = row.manual == 1L,
                )
            },
            evidence = queries.selectEvidence().executeAsList().map { row ->
                TransactionEvidence(
                    id = row.id,
                    transactionId = row.transactionId,
                    smsId = row.smsId,
                    role = EvidenceRole.valueOf(row.role),
                )
            },
            merchants = queries.selectMerchants().executeAsList().map { row ->
                Merchant(row.id, row.displayName, row.normalizedKey)
            },
            aliases = queries.selectAliases().executeAsList().map { row ->
                MerchantAlias(
                    id = row.id,
                    merchantId = row.merchantId,
                    patternType = AliasType.valueOf(row.patternType),
                    pattern = row.pattern,
                    priority = row.priority.toInt(),
                    source = AliasSource.valueOf(row.source),
                )
            },
            categoryRules = queries.selectRules().executeAsList().map { row ->
                CategoryRule(
                    id = row.id,
                    matchType = MatchType.valueOf(row.matchType),
                    pattern = row.pattern,
                    categoryId = row.categoryId,
                    priority = row.priority.toInt(),
                    source = RuleSource.valueOf(row.source),
                )
            },
            corrections = queries.selectCorrections().executeAsList().map { row ->
                Correction(
                    id = row.id,
                    dedupKey = row.dedupKey,
                    field = CorrectionField.valueOf(row.correctionField),
                    previousValue = row.previousValue,
                    updatedValue = row.updatedValue,
                    applyForward = row.applyForward == 1L,
                    createdAt = Instant.ofEpochMilli(row.createdAt),
                )
            },
            possibleDuplicates = queries.selectDuplicates().executeAsList().map { row ->
                PossibleDuplicate(row.id, row.transactionId, row.otherTransactionId)
            },
            categories = queries.selectCategories().executeAsList()
                .filterNot { it.systemRow == 1L }
                .map { row ->
                    Category(
                        id = row.id,
                        parentId = row.parentId,
                        nameEn = row.nameEn,
                        nameAr = row.nameAr,
                        slug = row.slug,
                        sortOrder = row.sortOrder.toInt(),
                        system = false,
                    )
                },
            reviewDismissals = queries.selectDismissals().executeAsList().map { row ->
                ReviewDismissal(
                    sender = row.sender,
                    bodyHash = row.bodyHash,
                    receivedAt = Instant.ofEpochMilli(row.receivedAt),
                    providerMessageId = row.providerMessageId,
                )
            },
        )
    }

    fun save(state: LedgerState) {
        val queries = database.expenseQueries
        database.transaction {
            queries.deleteCandidates()
            queries.deleteEvidence()
            queries.deleteDuplicates()
            queries.deleteTransactions()
            queries.deleteAttempts()
            queries.deleteDismissals()
            queries.deleteMessages()
            queries.deleteAccounts()
            queries.deleteMerchants()
            queries.deleteAliases()
            queries.deleteRules()
            queries.deleteCorrections()
            queries.deleteCategories()
            state.messages.forEach { sms ->
                queries.insertSms(
                    id = sms.id,
                    sender = sms.sender,
                    body = sms.body,
                    bodyHash = sms.bodyHash,
                    providerMessageId = sms.providerMessageId,
                    receivedAt = sms.receivedAt.toEpochMilli(),
                )
            }
            state.attempts.forEach { attempt ->
                queries.insertAttempt(
                    id = attempt.id,
                    smsId = attempt.smsId,
                    pipelineVersion = attempt.pipelineVersion,
                    profileId = attempt.profileId,
                    profileVersion = attempt.profileVersion,
                    templateId = attempt.templateId,
                    status = attempt.status.name,
                    eventType = attempt.eventType.name,
                    confidence = attempt.confidence?.toLong(),
                    error = attempt.error,
                )
                attempt.extraction?.candidates?.forEachIndexed { index, candidate ->
                    queries.insertCandidate(
                        attemptId = attempt.id,
                        position = index.toLong(),
                        eventType = candidate.eventType.name,
                        spendEffect = candidate.spendEffect.name,
                        direction = candidate.direction.name,
                        amountMinor = candidate.amount?.amountMinor,
                        amountCurrency = candidate.amount?.currency?.code,
                        merchantRaw = candidate.merchantRaw,
                        occurredAt = candidate.occurredAt?.toString(),
                        reference = candidate.reference,
                        accountMask = candidate.accountMask,
                        accountKind = candidate.accountKind?.name,
                        balanceMinor = candidate.balance?.amountMinor,
                        balanceCurrency = candidate.balance?.currency?.code,
                        foreignMinor = candidate.foreignAmount?.amountMinor,
                        foreignCurrency = candidate.foreignAmount?.currency?.code,
                        installmentIndex = candidate.installmentIndex?.toLong(),
                        installmentCount = candidate.installmentCount?.toLong(),
                    )
                }
            }
            state.accounts.forEach { account ->
                queries.insertAccount(
                    id = account.id,
                    institutionId = account.institutionId,
                    kind = account.kind.name,
                    mask = account.mask,
                    currency = account.currency.code,
                    displayName = account.displayName,
                )
            }
            state.merchants.forEach { merchant ->
                queries.insertMerchant(merchant.id, merchant.displayName, merchant.normalizedKey)
            }
            state.aliases.forEach { alias ->
                queries.insertAlias(
                    id = alias.id,
                    merchantId = alias.merchantId,
                    patternType = alias.patternType.name,
                    pattern = alias.pattern,
                    priority = alias.priority.toLong(),
                    source = alias.source.name,
                )
            }
            CategorySeed.all.forEach { insertCategory(it, system = true) }
            state.categories.forEach { insertCategory(it, system = false) }
            state.categoryRules.forEach { rule ->
                queries.insertRule(
                    id = rule.id,
                    matchType = rule.matchType.name,
                    pattern = rule.pattern,
                    categoryId = rule.categoryId,
                    priority = rule.priority.toLong(),
                    source = rule.source.name,
                )
            }
            state.corrections.forEach { correction ->
                queries.insertCorrection(
                    id = correction.id,
                    dedupKey = correction.dedupKey,
                    field = correction.field.name,
                    previousValue = correction.previousValue,
                    updatedValue = correction.updatedValue,
                    applyForward = if (correction.applyForward) 1L else 0L,
                    createdAt = correction.createdAt.toEpochMilli(),
                )
            }
            state.transactions.forEach { tx ->
                queries.insertTransaction(
                    id = tx.id,
                    dedupKey = tx.dedupKey,
                    smsId = tx.smsId,
                    institutionId = tx.institutionId,
                    accountId = tx.accountId,
                    eventType = tx.eventType.name,
                    spendEffect = tx.spendEffect.name,
                    status = tx.status.name,
                    amountMinor = tx.amount.amountMinor,
                    amountCurrency = tx.amount.currency.code,
                    amountText = amountText(tx.amount),
                    direction = tx.direction.name,
                    occurredAt = tx.occurredAt.toEpochMilli(),
                    occurredCivil = tx.occurredCivil.toString(),
                    occurredSource = tx.occurredSource.name,
                    merchantRaw = tx.merchantRaw,
                    merchantId = tx.merchantId,
                    categoryId = tx.categoryId,
                    categorySource = tx.categorySource.name,
                    reference = tx.reference,
                    balanceMinor = tx.balance?.amountMinor,
                    balanceCurrency = tx.balance?.currency?.code,
                    foreignMinor = tx.foreignAmount?.amountMinor,
                    foreignCurrency = tx.foreignAmount?.currency?.code,
                    duplicateOfId = tx.duplicateOfId,
                    linkedTransactionId = tx.linkedTransactionId,
                    includeInSpend = if (tx.includeInSpend) 1L else 0L,
                    pipelineVersion = tx.pipelineVersion,
                    profileVersion = tx.profileVersion,
                    installmentIndex = tx.installmentIndex?.toLong(),
                    installmentCount = tx.installmentCount?.toLong(),
                    manual = if (tx.manual) 1L else 0L,
                    revision = PipelineMetadata.VERSION,
                )
            }
            state.evidence.forEach { evidence ->
                queries.insertEvidence(evidence.id, evidence.transactionId, evidence.smsId, evidence.role.name)
            }
            state.possibleDuplicates.forEach { duplicate ->
                queries.insertDuplicate(duplicate.id, duplicate.transactionId, duplicate.otherTransactionId)
            }
            state.reviewDismissals.forEach { dismissal ->
                queries.insertDismissal(
                    sender = dismissal.sender,
                    bodyHash = dismissal.bodyHash,
                    receivedAt = dismissal.receivedAt.toEpochMilli(),
                    providerMessageId = dismissal.providerMessageId,
                )
            }
        }
    }

    fun search(rawQuery: String): List<SearchMatch> {
        val needle = rawQuery.trim()
        if (needle.isEmpty()) return emptyList()
        val queries = database.expenseQueries
        val hits = linkedMapOf<String, MutableSearchHit>()
        fun add(transactionId: String?, smsId: String, field: SearchField, sortAt: Long) {
            val key = transactionId ?: "sms:$smsId"
            val current = hits.getOrPut(key) { MutableSearchHit(transactionId, smsId, linkedSetOf(), sortAt) }
            current.fields += field
            if (sortAt > current.sortAt) current.sortAt = sortAt
        }
        queries.matchTransactionBody(needle).executeAsList().forEach {
            add(it.transactionId, it.smsId, SearchField.BODY, it.sortAt)
        }
        queries.matchMerchant(needle).executeAsList().forEach {
            add(it.transactionId, it.smsId, SearchField.MERCHANT, it.sortAt)
        }
        queries.matchReference(needle).executeAsList().forEach {
            add(it.transactionId, it.smsId, SearchField.REFERENCE, it.sortAt)
        }
        queries.matchAmount(needle).executeAsList().forEach {
            add(it.transactionId, it.smsId, SearchField.AMOUNT, it.sortAt)
        }
        val categories = queries.selectCategories().executeAsList().map { row ->
            Category(
                id = row.id,
                parentId = row.parentId,
                nameEn = row.nameEn,
                nameAr = row.nameAr,
                slug = row.slug,
                sortOrder = row.sortOrder.toInt(),
                system = row.systemRow == 1L,
            )
        }
        val matchedCategories = categories.filter { category ->
            category.nameEn.contains(needle, ignoreCase = true) ||
                category.nameAr.contains(needle) ||
                category.slug.contains(needle, ignoreCase = true)
        }.map { it.id }.toSet()
        val expanded = CategoryCatalog.descendants(matchedCategories, categories)
        if (expanded.isNotEmpty()) {
            queries.matchCategory(expanded).executeAsList().forEach {
                add(it.transactionId, it.smsId, SearchField.CATEGORY, it.sortAt)
            }
        }
        val transactionSms = hits.values.map { it.smsId }.toSet()
        queries.matchSmsBody(needle).executeAsList()
            .filter { it.smsId !in transactionSms }
            .forEach { add(transactionId = null, smsId = it.smsId, field = SearchField.BODY, sortAt = it.sortAt) }
        return hits.values
            .map { SearchMatch(it.transactionId, it.smsId, it.fields.toSet(), it.sortAt) }
            .sortedWith(compareByDescending<SearchMatch> { it.sortAt }.thenByDescending { it.smsId.orEmpty() })
    }

    fun snapshot(matches: List<SearchMatch>): LedgerState {
        if (matches.isEmpty()) return LedgerState(categories = customCategories())
        val queries = database.expenseQueries
        val smsIds = matches.mapNotNull { it.smsId }.distinct()
        val transactionIds = matches.mapNotNull { it.transactionId }.distinct()
        val messages = if (smsIds.isEmpty()) {
            emptyList()
        } else {
            queries.selectMessagesByIds(smsIds).executeAsList().map { row ->
                StoredSms(
                    id = row.id,
                    sender = row.sender,
                    body = row.body,
                    bodyHash = row.bodyHash,
                    providerMessageId = row.providerMessageId,
                    receivedAt = Instant.ofEpochMilli(row.receivedAt),
                )
            }
        }
        val transactions = if (transactionIds.isEmpty()) {
            emptyList()
        } else {
            queries.selectTransactionsByIds(transactionIds).executeAsList().map { row ->
                Transaction(
                    id = row.id,
                    dedupKey = row.dedupKey,
                    smsId = row.smsId,
                    institutionId = row.institutionId,
                    accountId = row.accountId,
                    eventType = FinancialEventType.valueOf(row.eventType),
                    spendEffect = SpendEffect.valueOf(row.spendEffect),
                    status = TransactionStatus.valueOf(row.status),
                    amount = Money(row.amountMinor, Currency.of(row.amountCurrency)),
                    direction = Direction.valueOf(row.direction),
                    occurredAt = Instant.ofEpochMilli(row.occurredAt),
                    occurredCivil = LocalDateTime.parse(row.occurredCivil),
                    occurredSource = OccurredSource.valueOf(row.occurredSource),
                    merchantRaw = row.merchantRaw,
                    merchantId = row.merchantId,
                    categoryId = row.categoryId,
                    categorySource = CategorySource.valueOf(row.categorySource),
                    reference = row.reference,
                    balance = money(row.balanceMinor, row.balanceCurrency),
                    foreignAmount = money(row.foreignMinor, row.foreignCurrency),
                    duplicateOfId = row.duplicateOfId,
                    linkedTransactionId = row.linkedTransactionId,
                    includeInSpend = row.includeInSpend == 1L,
                    pipelineVersion = row.pipelineVersion,
                    profileVersion = row.profileVersion,
                    installmentIndex = row.installmentIndex?.toInt(),
                    installmentCount = row.installmentCount?.toInt(),
                    manual = row.manual == 1L,
                )
            }
        }
        return LedgerState(
            messages = messages,
            transactions = transactions,
            merchants = queries.selectMerchants().executeAsList().map { Merchant(it.id, it.displayName, it.normalizedKey) },
            categories = customCategories(),
        )
    }

    /**
     * Six counts over six populations. None of them stands in for another, so
     * two screens reading this cannot disagree while both are right.
     */
    fun storedTally(): expense.ingest.IngestTally {
        val queries = database.expenseQueries
        return expense.ingest.IngestTally(
            smsScanned = queries.countMessages().executeAsOne().toInt(),
            financialEvents = queries.countFinancialEvents().executeAsOne().toInt(),
            postedTransactions = queries.countPostedTransactions().executeAsOne().toInt(),
            reviewItems = queries.countOpenReview().executeAsOne().toInt(),
            spendTransactions = queries.countSpendTransactions().executeAsOne().toInt(),
            excludedFinancialEvents = queries.countExcludedFinancialEvents().executeAsOne().toInt(),
        )
    }

    fun reviewWindow(offset: Int, limit: Int): ReviewWindow {
        require(limit > 0)
        val queries = database.expenseQueries
        val total = queries.countOpenReview().executeAsOne().toInt()
        val start = reviewOffset(total, offset, limit)
        if (total == 0) return ReviewWindow(emptyList(), 0, 0)
        val rows = queries.selectOpenReviewPage(limit.toLong(), start.toLong()).executeAsList().map { row ->
            ReviewRecord(
                attemptId = row.attemptId,
                smsId = row.smsId,
                status = ParseStatus.valueOf(row.status),
                sender = row.sender,
                receivedAt = Instant.ofEpochMilli(row.receivedAt),
                body = row.body,
                pipelineVersion = row.pipelineVersion,
                holdReason = row.holdReason,
            )
        }
        return ReviewWindow(rows, start, total)
    }

    /**
     * Re-runs the current semantic pipeline on stored review rows and retained
     * bodies that have no open attempt. One page is one database transaction.
     * A row is stamped with [pipelineVersion] inside that transaction, so a
     * process death retries only the uncommitted page. Posted [ParseStatus.PARSED]
     * rows are left alone. Returns how many messages were visited.
     */
    fun reclassifyRetained(
        pageSize: Int,
        maxPages: Int = Int.MAX_VALUE,
        pipelineVersion: String = PipelineMetadata.VERSION,
        newAttemptId: () -> String,
        interpret: (sender: String, body: String) -> StoredInterpretation,
        post: (LedgerState, StoredSms, StoredInterpretation) -> LedgerState,
    ): Int {
        require(pageSize > 0)
        require(maxPages > 0)
        var pages = 0
        var visited = 0
        while (pages < maxPages) {
            val review = staleReview(pipelineVersion, pageSize)
            val orphans = if (review.size < pageSize) staleOrphans(pageSize - review.size) else emptyList()
            val page = review + orphans
            if (page.isEmpty()) return visited
            val decided = page.map { row -> row to interpret(row.sender, row.body) }
            database.transaction {
                val needsPost = decided.any { (_, decision) -> decision.posts }
                val before = if (needsPost) loadWorkingSet() else null
                var working = before
                if (before != null) {
                    for ((row, decision) in decided) {
                        if (!decision.posts) continue
                        val message = checkNotNull(working).messages.first { it.id == row.smsId }
                        working = post(checkNotNull(working), message, decision)
                    }
                }
                decided.forEach { (row, decision) ->
                    rewriteStored(row, decision, pipelineVersion, newAttemptId)
                }
                if (before != null && working != null) appendInside(before, working)
            }
            pages++
            visited += page.size
        }
        return visited
    }

    private fun staleReview(pipelineVersion: String, limit: Int): List<StaleSms> {
        return database.expenseQueries.selectStaleReview(pipelineVersion, limit.toLong()).executeAsList().mapNotNull { row ->
            val body = row.body ?: return@mapNotNull null
            StaleSms(
                attemptId = row.attemptId,
                smsId = row.smsId,
                sender = row.sender,
                body = body,
                receivedAt = row.receivedAt,
                bodyHash = row.bodyHash,
                providerMessageId = row.providerMessageId,
            )
        }
    }

    private fun staleOrphans(limit: Int): List<StaleSms> {
        return database.expenseQueries.selectStaleOrphans(limit.toLong()).executeAsList().mapNotNull { row ->
            val body = row.body ?: return@mapNotNull null
            StaleSms(
                attemptId = row.attemptId,
                smsId = row.smsId,
                sender = row.sender,
                body = body,
                receivedAt = row.receivedAt,
                bodyHash = row.bodyHash,
                providerMessageId = row.providerMessageId,
            )
        }
    }

    private fun rewriteStored(
        row: StaleSms,
        decision: StoredInterpretation,
        pipelineVersion: String,
        newAttemptId: () -> String,
    ) {
        val queries = database.expenseQueries
        val attemptId = row.attemptId ?: if (decision.retainBody || decision.extraction != null) {
            newAttemptId()
        } else {
            null
        }
        if (attemptId == null) {
            queries.clearSmsBody(row.smsId)
            return
        }
        if (row.attemptId == null) {
            queries.insertAttempt(
                id = attemptId,
                smsId = row.smsId,
                pipelineVersion = pipelineVersion,
                profileId = decision.profile?.id,
                profileVersion = decision.profile?.version,
                templateId = decision.templateId,
                status = decision.status.name,
                eventType = decision.eventType.name,
                confidence = decision.extraction?.confidence?.toLong(),
                error = decision.error,
            )
        } else {
            queries.updateAttempt(
                pipelineVersion = pipelineVersion,
                profileId = decision.profile?.id,
                profileVersion = decision.profile?.version,
                templateId = decision.templateId,
                status = decision.status.name,
                eventType = decision.eventType.name,
                confidence = decision.extraction?.confidence?.toLong(),
                error = decision.error,
                id = attemptId,
            )
            queries.deleteCandidatesForAttempt(attemptId)
        }
        if (!decision.retainBody) queries.clearSmsBody(row.smsId)
        decision.extraction?.candidates?.forEachIndexed { index, candidate ->
            queries.insertCandidate(
                attemptId = attemptId,
                position = index.toLong(),
                eventType = candidate.eventType.name,
                spendEffect = candidate.spendEffect.name,
                direction = candidate.direction.name,
                amountMinor = candidate.amount?.amountMinor,
                amountCurrency = candidate.amount?.currency?.code,
                merchantRaw = candidate.merchantRaw,
                occurredAt = candidate.occurredAt?.toString(),
                reference = candidate.reference,
                accountMask = candidate.accountMask,
                accountKind = candidate.accountKind?.name,
                balanceMinor = candidate.balance?.amountMinor,
                balanceCurrency = candidate.balance?.currency?.code,
                foreignMinor = candidate.foreignAmount?.amountMinor,
                foreignCurrency = candidate.foreignAmount?.currency?.code,
                installmentIndex = candidate.installmentIndex?.toLong(),
                installmentCount = candidate.installmentCount?.toLong(),
            )
        }
    }

    private data class StaleSms(
        val attemptId: String?,
        val smsId: String,
        val sender: String,
        val body: String,
        val receivedAt: Long,
        val bodyHash: String,
        val providerMessageId: String?,
    )

    fun append(previous: LedgerState, next: LedgerState) {
        database.transaction { appendInside(previous, next) }
    }

    private fun appendInside(previous: LedgerState, next: LedgerState) {
        val queries = database.expenseQueries
            val previousMessageIds = previous.messages.map { it.id }.toSet()
            next.messages.filter { it.id !in previousMessageIds }.forEach { sms ->
                queries.insertSms(
                    id = sms.id,
                    sender = sms.sender,
                    body = sms.body,
                    bodyHash = sms.bodyHash,
                    providerMessageId = sms.providerMessageId,
                    receivedAt = sms.receivedAt.toEpochMilli(),
                )
            }
            val previousAttemptIds = previous.attempts.map { it.id }.toSet()
            next.attempts.filter { it.id !in previousAttemptIds }.forEach { attempt ->
                queries.insertAttempt(
                    id = attempt.id,
                    smsId = attempt.smsId,
                    pipelineVersion = attempt.pipelineVersion,
                    profileId = attempt.profileId,
                    profileVersion = attempt.profileVersion,
                    templateId = attempt.templateId,
                    status = attempt.status.name,
                    eventType = attempt.eventType.name,
                    confidence = attempt.confidence?.toLong(),
                    error = attempt.error,
                )
                attempt.extraction?.candidates?.forEachIndexed { index, candidate ->
                    queries.insertCandidate(
                        attemptId = attempt.id,
                        position = index.toLong(),
                        eventType = candidate.eventType.name,
                        spendEffect = candidate.spendEffect.name,
                        direction = candidate.direction.name,
                        amountMinor = candidate.amount?.amountMinor,
                        amountCurrency = candidate.amount?.currency?.code,
                        merchantRaw = candidate.merchantRaw,
                        occurredAt = candidate.occurredAt?.toString(),
                        reference = candidate.reference,
                        accountMask = candidate.accountMask,
                        accountKind = candidate.accountKind?.name,
                        balanceMinor = candidate.balance?.amountMinor,
                        balanceCurrency = candidate.balance?.currency?.code,
                        foreignMinor = candidate.foreignAmount?.amountMinor,
                        foreignCurrency = candidate.foreignAmount?.currency?.code,
                        installmentIndex = candidate.installmentIndex?.toLong(),
                        installmentCount = candidate.installmentCount?.toLong(),
                    )
                }
            }
            val previousAccountIds = previous.accounts.map { it.id }.toSet()
            next.accounts.filter { it.id !in previousAccountIds }.forEach { account ->
                queries.insertAccount(
                    id = account.id,
                    institutionId = account.institutionId,
                    kind = account.kind.name,
                    mask = account.mask,
                    currency = account.currency.code,
                    displayName = account.displayName,
                )
            }
            val previousMerchantIds = previous.merchants.map { it.id }.toSet()
            next.merchants.filter { it.id !in previousMerchantIds }.forEach { merchant ->
                queries.insertMerchant(merchant.id, merchant.displayName, merchant.normalizedKey)
            }
            val previousAliasIds = previous.aliases.map { it.id }.toSet()
            next.aliases.filter { it.id !in previousAliasIds }.forEach { alias ->
                queries.insertAlias(
                    id = alias.id,
                    merchantId = alias.merchantId,
                    patternType = alias.patternType.name,
                    pattern = alias.pattern,
                    priority = alias.priority.toLong(),
                    source = alias.source.name,
                )
            }
            val previousCategoryIds = previous.categories.map { it.id }.toSet()
            next.categories.filter { it.id !in previousCategoryIds }.forEach { insertCategory(it, system = false) }
            val previousRuleIds = previous.categoryRules.map { it.id }.toSet()
            next.categoryRules.filter { it.id !in previousRuleIds }.forEach { rule ->
                queries.insertRule(
                    id = rule.id,
                    matchType = rule.matchType.name,
                    pattern = rule.pattern,
                    categoryId = rule.categoryId,
                    priority = rule.priority.toLong(),
                    source = rule.source.name,
                )
            }
            val previousCorrectionIds = previous.corrections.map { it.id }.toSet()
            next.corrections.filter { it.id !in previousCorrectionIds }.forEach { correction ->
                queries.insertCorrection(
                    id = correction.id,
                    dedupKey = correction.dedupKey,
                    field = correction.field.name,
                    previousValue = correction.previousValue,
                    updatedValue = correction.updatedValue,
                    applyForward = if (correction.applyForward) 1L else 0L,
                    createdAt = correction.createdAt.toEpochMilli(),
                )
            }
            val previousTransactions = previous.transactions.associateBy { it.id }
            next.transactions.forEach { tx ->
                val prior = previousTransactions[tx.id]
                if (prior == null) {
                    insertTransaction(tx)
                } else if (prior != tx) {
                    queries.deleteTransactionById(tx.id)
                    insertTransaction(tx)
                }
            }
            val previousEvidenceIds = previous.evidence.map { it.id }.toSet()
            next.evidence.filter { it.id !in previousEvidenceIds }.forEach { evidence ->
                queries.insertEvidence(evidence.id, evidence.transactionId, evidence.smsId, evidence.role.name)
            }
            val previousDuplicateIds = previous.possibleDuplicates.map { it.id }.toSet()
            next.possibleDuplicates.filter { it.id !in previousDuplicateIds }.forEach { duplicate ->
                queries.insertDuplicate(duplicate.id, duplicate.transactionId, duplicate.otherTransactionId)
            }
            val previousDismissals = previous.reviewDismissals.toSet()
            next.reviewDismissals.filter { it !in previousDismissals }.forEach { dismissal ->
                queries.insertDismissal(
                    sender = dismissal.sender,
                    bodyHash = dismissal.bodyHash,
                    receivedAt = dismissal.receivedAt.toEpochMilli(),
                    providerMessageId = dismissal.providerMessageId,
                )
            }
    }

    fun update(transform: (LedgerState) -> LedgerState): LedgerState {
        val next = transform(load())
        save(next)
        return next
    }

    /** Highest provider row id stored, or null before the first sync. */
    fun inboxWatermark(): Long? {
        return database.expenseQueries.selectInboxWatermark().executeAsOneOrNull()?.providerMessageId
    }

    fun adoptProviderId(smsId: String, providerMessageId: String) {
        database.expenseQueries.updateSmsProviderId(providerMessageId = providerMessageId, id = smsId)
    }

    fun senderEvidence(): List<SenderEvidence> {
        return database.expenseQueries.selectSenderEvidence().executeAsList().map { row ->
            SenderEvidence(
                sender = row.sender,
                financialEvents = row.financialEvents.toInt(),
                movementEvents = row.movementEvents.toInt(),
                nonFinancialEvents = row.nonFinancialEvents.toInt(),
                eventTypes = row.eventTypes.split(SET_SEPARATOR)
                    .filter { it.isNotBlank() }
                    .mapNotNull { name -> runCatching { FinancialEventType.valueOf(name) }.getOrNull() }
                    .toSet(),
                instrumentMasks = row.instrumentMasks.split(SET_SEPARATOR).filter { it.isNotBlank() }.toSet(),
                instrumentKinds = row.instrumentKinds.split(SET_SEPARATOR)
                    .filter { it.isNotBlank() }
                    .mapNotNull { name -> runCatching { AccountKind.valueOf(name) }.getOrNull() }
                    .toSet(),
            )
        }
    }

    fun saveSenderEvidence(records: Collection<SenderEvidence>) {
        if (records.isEmpty()) return
        val queries = database.expenseQueries
        database.transaction {
            records.forEach { record ->
                queries.insertSenderEvidence(
                    sender = record.sender,
                    institutionId = senderInstitutionId(record.sender),
                    financialEvents = record.financialEvents.toLong(),
                    movementEvents = record.movementEvents.toLong(),
                    nonFinancialEvents = record.nonFinancialEvents.toLong(),
                    eventTypes = record.eventTypes.joinToString(SET_SEPARATOR) { it.name },
                    instrumentMasks = record.instrumentMasks.joinToString(SET_SEPARATOR),
                    instrumentKinds = record.instrumentKinds.joinToString(SET_SEPARATOR) { it.name },
                )
            }
        }
    }

    /**
     * Re-reads every posted transaction whose SMS this device still holds and
     * writes the newer reading onto the row that is already there.
     *
     * This is the half of a revision that reclassifying review rows cannot do.
     * A transaction posted under an older reading is otherwise frozen with it:
     * a card the old instrument matcher could not see stays unassociated
     * forever, a credit-card payment keeps counting as spending, and a
     * misordered date keeps its wrong civil time. Updating in place keeps the
     * row id and the dedup key, so user corrections still apply and no second
     * transaction appears for the same message.
     *
     * One page is one database transaction, and each row is stamped with
     * [revision] inside it, so a process death repeats only the page that did
     * not commit and a second run over stamped rows changes nothing.
     *
     * Returns how many rows were rewritten.
     */
    fun reviseStoredTransactions(
        pageSize: Int,
        revision: String = PipelineMetadata.VERSION,
        interpret: (sender: String, body: String) -> StoredInterpretation,
        newAccountId: () -> String,
    ): Int {
        require(pageSize > 0)
        var revised = 0
        while (true) {
            val page = database.expenseQueries
                .selectRevisableTransactions(revision, pageSize.toLong())
                .executeAsList()
            if (page.isEmpty()) return revised
            val readings = page.map { row -> row to interpret(row.sender, checkNotNull(row.body)) }
            database.transaction {
                for ((row, reading) in readings) {
                    if (reviseRow(row, reading, revision, newAccountId)) revised++
                }
            }
        }
    }

    fun staleRevisionCount(revision: String = PipelineMetadata.VERSION): Int {
        return database.expenseQueries.countStaleRevisions(revision).executeAsOne().toInt()
    }

    private fun reviseRow(
        row: SelectRevisableTransactions,
        reading: StoredInterpretation,
        revision: String,
        newAccountId: () -> String,
    ): Boolean {
        val queries = database.expenseQueries
        val candidate = reading.candidate
        if (candidate == null) {
            queries.markTransactionRevision(revision = revision, id = row.transactionId)
            return false
        }
        val status = TransactionStatus.valueOf(row.status)
        val accountId = row.accountId ?: accountFor(row, candidate.accountMask, candidate.accountKind, newAccountId)
        val civil = candidate.occurredAt
        val occurredSource = if (civil != null) OccurredSource.SMS_FIELD else OccurredSource.valueOf(row.occurredSource)
        val occurredCivil = civil ?: LocalDateTime.parse(row.occurredCivil)
        val corrected = queries.countCorrectionsFor(
            dedupKey = row.dedupKey,
            field = CorrectionField.INCLUDE_IN_SPEND.name,
        ).executeAsOne() > 0
        val includeInSpend = if (corrected) {
            row.includeInSpend == 1L
        } else {
            SpendPolicy.include(candidate.spendEffect, status)
        }
        queries.updateTransactionRevision(
            accountId = accountId,
            eventType = candidate.eventType.name,
            spendEffect = candidate.spendEffect.name,
            includeInSpend = if (includeInSpend) 1L else 0L,
            occurredAt = CairoClock.instantFrom(occurredCivil).toEpochMilli(),
            occurredCivil = occurredCivil.toString(),
            occurredSource = occurredSource.name,
            balanceMinor = candidate.balance?.amountMinor ?: row.balanceMinor,
            balanceCurrency = candidate.balance?.currency?.code ?: row.balanceCurrency,
            pipelineVersion = PipelineMetadata.VERSION,
            revision = revision,
            id = row.transactionId,
        )
        val changed = accountId != row.accountId ||
            candidate.eventType.name != row.eventType ||
            candidate.spendEffect.name != row.spendEffect ||
            (if (includeInSpend) 1L else 0L) != row.includeInSpend ||
            occurredCivil.toString() != row.occurredCivil
        return changed
    }

    private fun accountFor(
        row: SelectRevisableTransactions,
        mask: String?,
        kind: AccountKind?,
        newAccountId: () -> String,
    ): String? {
        if (mask.isNullOrBlank() || kind == null) return null
        val queries = database.expenseQueries
        val existing = queries.selectAccountId(
            institutionId = row.institutionId,
            kind = kind.name,
            mask = mask,
        ).executeAsOneOrNull()
        if (existing != null) return existing
        return newAccountId().also { id ->
            queries.insertAccount(
                id = id,
                institutionId = row.institutionId,
                kind = kind.name,
                mask = mask,
                currency = row.currency,
                displayName = "",
            )
        }
    }

    private fun customCategories(): List<Category> {
        return database.expenseQueries.selectCategories().executeAsList()
            .filterNot { it.systemRow == 1L }
            .map { row ->
                Category(
                    id = row.id,
                    parentId = row.parentId,
                    nameEn = row.nameEn,
                    nameAr = row.nameAr,
                    slug = row.slug,
                    sortOrder = row.sortOrder.toInt(),
                    system = false,
                )
            }
    }

    private fun insertTransaction(tx: Transaction) {
        database.expenseQueries.insertTransaction(
            id = tx.id,
            dedupKey = tx.dedupKey,
            smsId = tx.smsId,
            institutionId = tx.institutionId,
            accountId = tx.accountId,
            eventType = tx.eventType.name,
            spendEffect = tx.spendEffect.name,
            status = tx.status.name,
            amountMinor = tx.amount.amountMinor,
            amountCurrency = tx.amount.currency.code,
            amountText = amountText(tx.amount),
            direction = tx.direction.name,
            occurredAt = tx.occurredAt.toEpochMilli(),
            occurredCivil = tx.occurredCivil.toString(),
            occurredSource = tx.occurredSource.name,
            merchantRaw = tx.merchantRaw,
            merchantId = tx.merchantId,
            categoryId = tx.categoryId,
            categorySource = tx.categorySource.name,
            reference = tx.reference,
            balanceMinor = tx.balance?.amountMinor,
            balanceCurrency = tx.balance?.currency?.code,
            foreignMinor = tx.foreignAmount?.amountMinor,
            foreignCurrency = tx.foreignAmount?.currency?.code,
            duplicateOfId = tx.duplicateOfId,
            linkedTransactionId = tx.linkedTransactionId,
            includeInSpend = if (tx.includeInSpend) 1L else 0L,
            pipelineVersion = tx.pipelineVersion,
            profileVersion = tx.profileVersion,
            installmentIndex = tx.installmentIndex?.toLong(),
            installmentCount = tx.installmentCount?.toLong(),
            manual = if (tx.manual) 1L else 0L,
            revision = PipelineMetadata.VERSION,
        )
    }

    private fun insertCategory(category: Category, system: Boolean) {
        database.expenseQueries.insertCategory(
            id = category.id,
            parentId = category.parentId,
            nameEn = category.nameEn,
            nameAr = category.nameAr,
            slug = category.slug,
            sortOrder = category.sortOrder.toLong(),
            systemRow = if (system) 1L else 0L,
        )
    }

    private data class MutableSearchHit(
        val transactionId: String?,
        val smsId: String,
        val fields: MutableSet<SearchField>,
        var sortAt: Long,
    )

    /**
     * Rows written before the reading was stored carry no event type. They are
     * unknown rather than non-financial, and a revision pass replaces the value
     * with a real reading.
     */
    private fun eventTypeOf(stored: String?): FinancialEventType {
        if (stored.isNullOrBlank()) return FinancialEventType.OTHER_FINANCIAL
        return runCatching { FinancialEventType.valueOf(stored) }
            .getOrDefault(FinancialEventType.OTHER_FINANCIAL)
    }

    private fun money(minor: Long?, code: String?): Money? {
        if (minor == null || code == null) return null
        return Money(minor, Currency.of(code))
    }
}
