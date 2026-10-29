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

    /**
     * DAR-BE-153: the ids HotWax keeps as OPP manualRefNum, comma-joined and sorted — every SUCCESS SALE and CAPTURE, and a
     * SUCCESS AUTHORIZATION no successful CAPTURE or VOID names as its parent. Gorjana prod 2026-10-28: a card's OPP names
     * the CAPTURE (136/136), every other tender the SALE; Steve Madden: a cancelled order's OPP names the VOID (19/19).
     */
    static String paymentTransactionIds(Object transactions) {
        if (!(transactions instanceof Collection)) return ""
        List<Map> settled = ((Collection) transactions).findAll { Object t ->
            t instanceof Map && "SUCCESS".equalsIgnoreCase(((Map) t).get("status") as String)
        } as List<Map>
        Set<String> settledAuths = settled.findAll { kindOf(it) in ["CAPTURE", "VOID"] }
                .collect { legacyId(((Map) (it.get("parentTransaction") ?: [:])).get("id")) }.findAll { it } as Set
        return settled.findAll { Map t ->
            String kind = kindOf(t)
            kind == "SALE" || kind == "CAPTURE" || (kind == "AUTHORIZATION" && !settledAuths.contains(legacyId(t.get("id"))))
        }.collect { legacyId(it.get("id")) }.findAll { it }.unique().sort().join(",")
    }

    /**
     * DAR-BE-153: every SUCCESS transaction id on the order, any kind, comma-joined and sorted. The side a HotWax OPP
     * manualRefNum must be found in (paymentRefNums all in transactionIds): Steve Madden captures at ship and its OPP keeps
     * the AUTHORIZATION id, a cancelled order's OPP names the VOID, so no one id per payment fits every store.
     */
    static String transactionIds(Object transactions) {
        if (!(transactions instanceof Collection)) return ""
        return ((Collection) transactions).findAll { Object t ->
            t instanceof Map && "SUCCESS".equalsIgnoreCase(((Map) t).get("status") as String)
        }.collect { legacyId(((Map) it).get("id")) }.findAll { it }.unique().sort().join(",")
    }

    private static String kindOf(Map t) { return ((t.get("kind") ?: "") as String).toUpperCase() }

    /** gid://shopify/OrderTransaction/123 -> 123; a bare id stays as it is. */
    private static String legacyId(Object id) {
        String text = id?.toString()?.trim()
        return text ? text.substring(text.lastIndexOf("/") + 1) : null
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
