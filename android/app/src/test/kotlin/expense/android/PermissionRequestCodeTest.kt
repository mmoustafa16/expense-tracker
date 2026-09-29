package expense.android

import androidx.fragment.app.FragmentActivity
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/**
 * Activity Result permission launches use request codes at or above 65536.
 * Fragment 1.2.5 rejects those in validateRequestPermissionsRequestCode and
 * crashes immediately after unlock, when the SMS permission prompt is launched.
 */
class PermissionRequestCodeTest {
    @Test
    fun `fragment accepts the activity result permission request codes`() {
        val activity = allocate(FragmentActivity::class.java)
        val method = FragmentActivity::class.java.getMethod(
            "validateRequestPermissionsRequestCode",
            Int::class.javaPrimitiveType,
        )
        assertDoesNotThrow {
            method.invoke(activity, ACTIVITY_RESULT_MIN_REQUEST_CODE)
            method.invoke(activity, ACTIVITY_RESULT_MIN_REQUEST_CODE + 100_000)
        }
    }

    private fun allocate(type: Class<*>): Any {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val field = unsafeType.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocate = unsafeType.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, type)
    }

    private companion object {
        const val ACTIVITY_RESULT_MIN_REQUEST_CODE: Int = 65536
    }
}
