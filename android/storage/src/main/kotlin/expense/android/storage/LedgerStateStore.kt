package expense.android.storage

import expense.android.storage.db.ExpenseDatabase
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
import expense.parse.Direction
import expense.parse.Extraction
import expense.parse.ParseAttempt
import expense.parse.ParseStatus
import expense.parse.TransactionCandidate
import expense.parse.TransactionKind
import java.time.Instant
import java.time.LocalDateTime

class SqlDelightLedgerRepository(
    private val database: ExpenseDatabase,
) {
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

    private fun read(messages: List<StoredSms>): LedgerState {
        val queries = database.expenseQueries
        val candidates = queries.selectCandidates().executeAsList().groupBy { it.attemptId }
        return LedgerState(
            messages = messages,
            attempts = queries.selectAttempts().executeAsList().map { row ->
                val rows = candidates[row.id].orEmpty()
                val extraction = row.confidence?.let { confidence ->
                    Extraction(
                        confidence = confidence.toInt(),
                        candidates = rows.map { candidate ->
                            TransactionCandidate(
                                kind = TransactionKind.valueOf(candidate.kind),
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
                    confidence = row.confidence?.toInt(),
                    extraction = extraction,
                    error = row.error,
                )
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
                    kind = TransactionKind.valueOf(row.kind),
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
                    confidence = attempt.confidence?.toLong(),
                    error = attempt.error,
                )
                attempt.extraction?.candidates?.forEachIndexed { index, candidate ->
                    queries.insertCandidate(
                        attemptId = attempt.id,
                        position = index.toLong(),
                        kind = candidate.kind.name,
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
                    kind = tx.kind.name,
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
                    kind = TransactionKind.valueOf(row.kind),
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

    fun storedTally(): expense.ingest.IngestTally {
        val queries = database.expenseQueries
        val statuses = queries.countAttemptsByStatus().executeAsList().associate { it.status to it.total.toInt() }
        return expense.ingest.IngestTally(
            scanned = queries.countMessages().executeAsOne().toInt(),
            financial = queries.countRetainedMessages().executeAsOne().toInt(),
            matchedProfile = queries.countMatchedAttempts().executeAsOne().toInt(),
            unsupported = statuses[ParseStatus.UNSUPPORTED.name] ?: 0,
            parsed = statuses[ParseStatus.PARSED.name] ?: 0,
            posted = queries.countPostedTransactions().executeAsOne().toInt(),
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
            )
        }
        return ReviewWindow(rows, start, total)
    }

    fun append(previous: LedgerState, next: LedgerState) {
        val queries = database.expenseQueries
        database.transaction {
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
                    confidence = attempt.confidence?.toLong(),
                    error = attempt.error,
                )
                attempt.extraction?.candidates?.forEachIndexed { index, candidate ->
                    queries.insertCandidate(
                        attemptId = attempt.id,
                        position = index.toLong(),
                        kind = candidate.kind.name,
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
    }

    fun update(transform: (LedgerState) -> LedgerState): LedgerState {
        val next = transform(load())
        save(next)
        return next
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
            kind = tx.kind.name,
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

    private fun money(minor: Long?, code: String?): Money? {
        if (minor == null || code == null) return null
        return Money(minor, Currency.of(code))
    }
}
