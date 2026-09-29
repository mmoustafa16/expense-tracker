package expense.intelligence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SenderDiscoveryDiagnosticTest {
    private val diagnostic = SenderDiscoveryDiagnostic()

    @Test
    fun `sender summary counts classes and shapes without message contents`() {
        val purchase = "Your card was used for EGP 450.75 at TalabatXYZ on 15/01/2026 14:30 ref ZX91QQ card ending 9182"
        val transfer = "Transferred EGP 500.00 to SamirXYZ"
        val withdrawal = "Cash withdrawal of EGP 200.00 at ATMXYZ"
        val refund = "Refund of EGP 75.00 from ShopXYZ"
        val reversal = "Reversed EGP 30.00 at ShopXYZ"
        val fee = "A service fee of EGP 5.00 was applied"
        val balance = "Your available balance is EGP 1,250.00"
        val otp = "Your OTP is 482193"
        val promotion = "Save EGP 50 this weekend. Use code SAVE20"
        val messages = listOf(
            DiagnosticSms("SHOP", purchase),
            DiagnosticSms("SHOP", null),
            DiagnosticSms("12345", transfer),
            DiagnosticSms("201012345678", withdrawal),
            DiagnosticSms("Alert Line", refund),
            DiagnosticSms("Alert Line", reversal),
            DiagnosticSms("Alert Line", fee),
            DiagnosticSms("Alert Line", balance),
            DiagnosticSms("Alert Line", otp),
            DiagnosticSms("Alert Line", promotion),
        )
        val report = diagnostic.summarize(messages)
        val shop = report.senders.single { it.sender == "SHOP" }
        assertEquals(2, shop.messageCount)
        assertEquals("alphanumeric", shop.shape)
        assertEquals(1, shop.classCounts["purchase"])
        assertEquals(1, shop.classCounts["not_retained"])
        val purchaseFingerprint = shop.fingerprints.keys.single { it.contains("MASK_PRESENT") }
        assertTrue(purchaseFingerprint.contains("AMOUNT_PRESENT"))
        assertTrue(purchaseFingerprint.contains("DATE_PRESENT"))
        assertTrue(purchaseFingerprint.contains("TIME_PRESENT"))
        assertTrue(purchaseFingerprint.contains("REFERENCE_CUE"))
        assertEquals(1, shop.fingerprints["NOT_RETAINED"])

        assertEquals("short code", report.senders.single { it.sender == "12345" }.shape)
        assertEquals(1, report.senders.single { it.sender == "12345" }.classCounts["transfer"])
        assertEquals("long numeric", report.senders.single { it.sender == "201012345678" }.shape)
        assertEquals(1, report.senders.single { it.sender == "201012345678" }.classCounts["withdrawal"])

        val other = report.senders.single { it.sender == "Alert Line" }
        assertEquals("alphanumeric", other.shape)
        assertEquals(1, other.classCounts["refund"])
        assertEquals(1, other.classCounts["reversal"])
        assertEquals(1, other.classCounts["fee"])
        assertEquals(1, other.classCounts["balance"])
        assertEquals(2, other.classCounts["not_financial"])

        val shown = report.lines().joinToString("\n") + "\n" + report.toString()
        val secrets = listOf(
            purchase, transfer, withdrawal, refund, reversal, fee, balance, otp, promotion,
            "TalabatXYZ", "450.75", "15/01/2026", "14:30", "ZX91QQ", "9182",
            "SamirXYZ", "500.00", "ATMXYZ", "200.00", "ShopXYZ", "75.00", "30.00",
            "5.00", "1,250.00", "482193", "SAVE20", "weekend", "EGP",
        )
        secrets.forEach { secret ->
            assertFalse(shown.contains(secret), secret)
        }
        report.senders.flatMap { it.fingerprints.keys }.flatMap { it.split("+") }.forEach { token ->
            assertTrue(token in MessageFingerprint.tokens, token)
        }
        val shapeLabels = setOf("short code", "alphanumeric", "long numeric", "other")
        assertTrue(report.senders.all { it.shape in shapeLabels })
        assertEquals(purchase, messages[0].body)
        assertEquals(null, messages[1].body)
    }

    @Test
    fun `an empty ingest produces an empty summary`() {
        val report = diagnostic.summarize(emptyList())
        assertEquals(listOf("No ingested messages."), report.lines())
        assertTrue(report.senders.isEmpty())
    }
}
