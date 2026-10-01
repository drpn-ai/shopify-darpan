package shopify.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-064 Task 8. Y when any SUCCESS transaction is a SALE, CAPTURE or AUTHORIZATION. AUTHORIZATION
 * is in because HotWax writes the OPP at import, before capture; leaving it out would call every
 * authorised-not-captured order unpaid.
 */
class ShopifyOrderPaymentSignalTests {

    private static Map t(String kind, String status, String gateway = "shopify_payments") {
        return [kind: kind, status: status, gateway: gateway]
    }

    @Test void aSuccessfulSaleIsAPayment() { assertEquals("Y", ShopifyOrderPaymentSignal.hasPaymentTransaction([t("SALE", "SUCCESS")])) }
    @Test void anAuthorizationAloneIsAPayment() { assertEquals("Y", ShopifyOrderPaymentSignal.hasPaymentTransaction([t("AUTHORIZATION", "SUCCESS")])) }
    @Test void aCaptureIsAPayment() { assertEquals("Y", ShopifyOrderPaymentSignal.hasPaymentTransaction([t("CAPTURE", "SUCCESS")])) }
    @Test void aGiftCardSaleIsAPayment() { assertEquals("Y", ShopifyOrderPaymentSignal.hasPaymentTransaction([t("SALE", "SUCCESS", "gift_card")])) }
    @Test void aFailedSaleIsNot() { assertEquals("N", ShopifyOrderPaymentSignal.hasPaymentTransaction([t("SALE", "FAILURE")])) }
    @Test void aPendingManualPaymentIsNot() { assertEquals("N", ShopifyOrderPaymentSignal.hasPaymentTransaction([t("SALE", "PENDING", "manual")])) }
    @Test void refundsAndVoidsAloneAreNot() { assertEquals("N", ShopifyOrderPaymentSignal.hasPaymentTransaction([t("REFUND", "SUCCESS"), t("VOID", "SUCCESS")])) }
    @Test void aRefundedSaleStillHadAPayment() { assertEquals("Y", ShopifyOrderPaymentSignal.hasPaymentTransaction([t("SALE", "SUCCESS"), t("REFUND", "SUCCESS")])) }
    @Test void noTransactionsIsN() {
        assertEquals("N", ShopifyOrderPaymentSignal.hasPaymentTransaction([]))
        assertEquals("N", ShopifyOrderPaymentSignal.hasPaymentTransaction(null))
    }
    @Test void caseOfKindAndStatusDoesNotMatter() { assertEquals("Y", ShopifyOrderPaymentSignal.hasPaymentTransaction([t("sale", "success")])) }

    @Test
    void theOrderExtractScriptDerivesTheFieldAndCompiles() {
        // The extract is a Moqui service script, which Gradle never compiles.
        String script = new File(System.getProperty("user.dir"),
                "src/main/groovy/shopify/reconciliation/automation/extractShopifyOrders.groovy").getText("UTF-8")
        new GroovyShell().parse(script, "extractShopifyOrders.groovy")
        org.junit.jupiter.api.Assertions.assertTrue(
                script.contains("normalizedRecord.hasPaymentTransaction = ShopifyOrderPaymentSignal.hasPaymentTransaction("))
    }

    @Test
    void summarizeCountsPaidAndUnpaidOrders() {
        assertEquals([paymentTransactionYCount: 2, paymentTransactionNCount: 1],
                ShopifyOrderPaymentSignal.summarize([[hasPaymentTransaction: "Y"], [hasPaymentTransaction: "N"], [hasPaymentTransaction: "Y"]]))
    }

    @Test
    void aNonEmptyExtractWithNoPaidOrderRaisesTheCanary() {
        // Review I3: under the one-directional rule a missing signal reads clean forever. P0 baseline is ~90% Y.
        String warning = ShopifyOrderPaymentSignal.canaryWarning([paymentTransactionYCount: 0, paymentTransactionNCount: 40])
        org.junit.jupiter.api.Assertions.assertNotNull(warning)
        org.junit.jupiter.api.Assertions.assertTrue(warning.contains("hasPaymentTransaction"))
    }

    @Test
    void noCanaryOnAnEmptyOrMixedExtract() {
        assertEquals(null, ShopifyOrderPaymentSignal.canaryWarning([paymentTransactionYCount: 0, paymentTransactionNCount: 0]))
        assertEquals(null, ShopifyOrderPaymentSignal.canaryWarning([paymentTransactionYCount: 3, paymentTransactionNCount: 40]))
    }

    @Test
    void theOrderExtractReportsTheCountsAndTheCanary() {
        String script = new File(System.getProperty("user.dir"),
                "src/main/groovy/shopify/reconciliation/automation/extractShopifyOrders.groovy").getText("UTF-8")
        org.junit.jupiter.api.Assertions.assertTrue(script.contains("ShopifyOrderPaymentSignal.summarize("))
        org.junit.jupiter.api.Assertions.assertTrue(script.contains("ShopifyOrderPaymentSignal.canaryWarning("))
    }
}
