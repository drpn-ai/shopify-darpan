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
        // DAR-BE-125 (R20): Y only when Shopify fulfilled the whole order; partial is N.
        org.junit.jupiter.api.Assertions.assertTrue(
                script.contains('normalizedRecord.isFulfilled = normalize(normalizedRecord.displayFulfillmentStatus) == "FULFILLED" ? "Y" : "N"'))
        org.junit.jupiter.api.Assertions.assertTrue(
                script.contains("normalizedRecord.paymentTransactionIds = ShopifyOrderPaymentSignal.paymentTransactionIds(normalizedRecord.transactions)"))
        org.junit.jupiter.api.Assertions.assertTrue(
                script.contains("normalizedRecord.transactionIds = ShopifyOrderPaymentSignal.transactionIds(normalizedRecord.transactions)"))
    }

    // DAR-BE-153. The ids HotWax keeps as OPP manualRefNum (gorjana probe 2026-10-28): a card's CAPTURE, a one-step SALE.
    private static Map tx(String id, String kind, String status = "SUCCESS", String parentId = null) {
        return [id: "gid://shopify/OrderTransaction/${id}".toString(), kind: kind, status: status, gateway: "shopify_payments",
                parentTransaction: parentId ? [id: "gid://shopify/OrderTransaction/${parentId}".toString()] : null]
    }

    @Test
    void paymentTransactionIdsAreTheSettledSalesAndCaptures() {
        // #GOR197298413: card authorised and captured, then an exchange top-up taken as a sale a month later.
        assertEquals("20766330257539,20853219360899", ShopifyOrderPaymentSignal.paymentTransactionIds([
                tx("20766295916675", "AUTHORIZATION"), tx("20766330257539", "CAPTURE", "SUCCESS", "20766295916675"),
                tx("20853219360899", "SALE")]))
    }

    @Test
    void anAuthorizationNothingCapturedCountsAndFailuresRefundsAndVoidsDoNot() {
        assertEquals("1", ShopifyOrderPaymentSignal.paymentTransactionIds([tx("1", "AUTHORIZATION")]))
        // #GOR197242121: a failed sale before the card went through.
        assertEquals("8352615268483", ShopifyOrderPaymentSignal.paymentTransactionIds([tx("8352576143491", "SALE", "FAILURE"),
                tx("8352578338947", "AUTHORIZATION"), tx("8352615268483", "CAPTURE", "SUCCESS", "8352578338947")]))
        assertEquals("5", ShopifyOrderPaymentSignal.paymentTransactionIds([tx("5", "SALE"), tx("6", "REFUND", "SUCCESS", "5"), tx("7", "VOID")]))
        // A capture that failed leaves its authorization the payment.
        assertEquals("1", ShopifyOrderPaymentSignal.paymentTransactionIds([tx("1", "AUTHORIZATION"), tx("2", "CAPTURE", "FAILURE", "1")]))
    }

    @Test
    void aVoidedAuthorizationIsNotAPayment() {
        // Steve Madden 2026-10-28: 19 cancelled orders, each an AUTHORIZATION voided by a VOID naming it; HotWax's OPP
        // names the VOID, so claiming the authorization flagged every one.
        assertEquals("", ShopifyOrderPaymentSignal.paymentTransactionIds([tx("1", "AUTHORIZATION"), tx("2", "VOID", "SUCCESS", "1")]))
        // A void that failed leaves its authorization the payment.
        assertEquals("1", ShopifyOrderPaymentSignal.paymentTransactionIds([tx("1", "AUTHORIZATION"), tx("2", "VOID", "FAILURE", "1")]))
    }

    @Test
    void transactionIdsAreEverySuccessfulTransactionOfAnyKind() {
        // Steve Madden 2026-10-28: capture-at-ship, so HotWax's OPP keeps the AUTHORIZATION id after Shopify captures,
        // and a cancelled order's OPP names the VOID. Every id HotWax may hold is here; failures are not.
        assertEquals("1,2,3,4", ShopifyOrderPaymentSignal.transactionIds([tx("1", "AUTHORIZATION"),
                tx("2", "CAPTURE", "SUCCESS", "1"), tx("3", "REFUND", "SUCCESS", "2"), tx("4", "VOID", "SUCCESS", "1"),
                tx("5", "SALE", "FAILURE"), tx("6", "SALE", "PENDING")]))
        assertEquals("", ShopifyOrderPaymentSignal.transactionIds([]))
        assertEquals("", ShopifyOrderPaymentSignal.transactionIds(null))
    }

    @Test
    void noTransactionsHasNoPaymentTransactionIds() {
        assertEquals("", ShopifyOrderPaymentSignal.paymentTransactionIds([]))
        assertEquals("", ShopifyOrderPaymentSignal.paymentTransactionIds(null))
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
