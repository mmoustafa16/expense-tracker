package expense.android.storage

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import expense.android.storage.db.ExpenseDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.concurrent.atomic.AtomicBoolean

internal object SqlCipherDrivers {
    private val loaded = AtomicBoolean(false)

    fun open(context: Context, passphrase: ByteArray): SqlDriver {
        if (loaded.compareAndSet(false, true)) {
            System.loadLibrary("sqlcipher")
        }
        val factory = SupportOpenHelperFactory(passphrase.copyOf())
        return AndroidSqliteDriver(
            schema = ExpenseDatabase.Schema,
            context = context,
            name = LedgerFiles.DATABASE_NAME,
            factory = factory,
        )
    }
}
