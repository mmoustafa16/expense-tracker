package expense.android.storage

import android.content.Context
import expense.ingest.IngestPipeline
import expense.parse.VerifiedBankCatalog
import java.io.File

object LedgerSessions {
    fun android(context: Context): LedgerSession {
        val appContext = context.applicationContext
        val databaseFile = appContext.getDatabasePath(LedgerFiles.DATABASE_NAME)
        val parent = databaseFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val wrapFile = File(appContext.filesDir, LedgerFiles.WRAP_FILE_NAME)
        return LedgerSession(
            databaseFile = databaseFile,
            vault = WrappedDatabaseKey(
                databaseFile = databaseFile,
                wrapFile = wrapFile,
                box = AndroidSecretKeyBox(),
            ),
            openDriver = { _, passphrase -> SqlCipherDrivers.open(appContext, passphrase) },
            pipeline = IngestPipeline(registry = VerifiedBankCatalog.registry()),
        )
    }
}
