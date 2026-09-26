package expense.merchants

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MerchantKeyTest {
    @Test
    fun `drops processor prefixes store numbers and alef variants`() {
        assertEquals("coffee shop", MerchantKey.normalize("VISA *COFFEE SHOP 12"))
        assertEquals("ماكولات", MerchantKey.normalize("مأكولات"))
        assertEquals("قهوه", MerchantKey.normalize("قهوة"))
    }
}

class MerchantDirectoryTest {
    @Test
    fun `exact raw alias wins over a normalized key`() {
        val target = Merchant("m-a", "Alpha", "cafe 123")
        val other = Merchant("m-b", "Beta", "cafe")
        val aliases = listOf(
            MerchantAlias("a1", "m-b", AliasType.NORMALIZED_KEY, "cafe 123", 100, AliasSource.SYSTEM),
            MerchantAlias("a2", "m-a", AliasType.EXACT_RAW, "Cafe 123", 200, AliasSource.USER),
        )
        val resolved = MerchantDirectory.resolve(
            raw = "Cafe 123",
            merchants = listOf(target, other),
            aliases = aliases,
            newId = { "new" },
        )
        assertEquals("m-a", resolved.merchant?.id)
    }
}
