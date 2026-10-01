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
}
