package shopify.reconciliation.automation

/**
 * DAR-BE-064: did this Shopify order take a payment HotWax should hold an OrderPaymentPreference for?
 * Compared by the presence rule against the OMS side's hasPaymentPreference, so both emit "Y"/"N".
 */
class ShopifyOrderPaymentSignal {

    static final List<String> PAYMENT_KINDS = ["SALE", "CAPTURE", "AUTHORIZATION"]

    static String hasPaymentTransaction(Object transactions) {
        if (!(transactions instanceof Collection)) return "N"
        boolean paid = ((Collection) transactions).any { Object t ->
            t instanceof Map &&
                    "SUCCESS".equalsIgnoreCase(((Map) t).get("status") as String) &&
                    PAYMENT_KINDS.contains(((((Map) t).get("kind") ?: "") as String).toUpperCase())
        }
        return paid ? "Y" : "N"
    }

    /** Y/N counts over an extract's records, for requestMetadata. */
    static Map<String, Integer> summarize(List records) {
        int y = 0, n = 0
        (records ?: []).each { Object r ->
            if (r instanceof Map && ((Map) r).get("hasPaymentTransaction") == "Y") y++ else n++
        }
        return [paymentTransactionYCount: y, paymentTransactionNCount: n]
    }

    /**
     * Canary (review I3). The presence OPP rule is one-directional (<=), so a Shopify side whose signal
     * went missing — every order N — would read clean forever. A non-empty extract with no paid order is
     * the symptom: gorjana's baseline is ~90% Y.
     */
    static String canaryWarning(Map summary) {
        int y = (summary?.paymentTransactionYCount ?: 0) as int
        int n = (summary?.paymentTransactionNCount ?: 0) as int
        if (n == 0 || y > 0) return null
        return "No order in this Shopify extract has hasPaymentTransaction=Y (${n} orders). The transactions " +
                "selection may not have come back; the presence OPP rule cannot fire on this run."
    }
}
