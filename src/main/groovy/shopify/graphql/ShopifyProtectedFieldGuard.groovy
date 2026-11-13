package shopify.graphql

import darpan.datasource.GraphQlDocumentCheck

/**
 * DAR-BE-260. Darpan reads Shopify customer data at protected-data Level 1 only: no customer email, phone, name or
 * address. Every Shopify call goes through ShopifyGraphqlTransport.execute, which asks this guard first, so a free-form
 * query (execute#ShopifyGraphql), an AI agent's query and a saved automation are held to the same line as the catalog.
 * A document the guard cannot read is refused, never waved through. Pure: no Moqui, no network.
 */
class ShopifyProtectedFieldGuard {

    /** Fields that carry a person's email, phone or name, or a whole address, wherever they appear. */
    static final Set<String> REFUSED_FIELDS = [
            "email", "phone", "emailAddress", "phoneNumber", "contactEmail", "defaultEmailAddress", "defaultPhoneNumber",
            "firstName", "lastName",
            "billingAddress", "shippingAddress", "defaultAddress", "addresses", "addressesV2", "displayAddress", "destination",
    ].asImmutable() as Set<String>

    /** Types that are themselves an address or a contact point; selecting into them is refused. */
    static final Set<String> REFUSED_TYPES = [
            "MailingAddress", "FulfillmentOrderDestination", "CustomerEmailAddress", "CustomerPhoneNumber",
    ].asImmutable() as Set<String>

    /** On a customer only these may be read: who it is, never what it is called or how to reach it. */
    static final Set<String> CUSTOMER_FIELDS_ALLOWED = ["id", "legacyResourceId", "__typename"].asImmutable() as Set<String>
    static final Set<String> CUSTOMER_STEPS = ["customer", "customers", "on:Customer"].asImmutable() as Set<String>

    /** The bulk mutation carries its real read as a string argument; that string is checked as a query of its own. */
    static final String BULK_RUN_QUERY = "bulkOperationRunQuery"

    /** Null when the document may be sent; otherwise the reason it may not, naming every refused field path. */
    static String refusal(String document, Map variables = [:]) {
        Map report = GraphQlDocumentCheck.fieldPaths(document)
        if (report.errors) return "Darpan could not check this Shopify query for customer data, so it was not sent: ${report.errors[0]}"
        Set<String> refused = new TreeSet<String>()
        ((List<List<String>>) report.paths).each { List<String> path -> if (isRefused(path)) refused << path.findAll { !it.startsWith("on:") || it.substring(3) in REFUSED_TYPES }.join(".") }

        Map bulkArgs = ((Map) report.rootArguments).get(BULK_RUN_QUERY) as Map
        if (bulkArgs != null) {
            Map arg = bulkArgs.get("query") as Map
            String inner = arg?.kind == "string" ? arg.value as String : arg?.kind == "variable" ? variables?.get(arg.name) as String : null
            if (!inner) return "Darpan could not check this Shopify bulk query for customer data, so it was not sent: its query is missing or not a plain string."
            String innerRefusal = refusal(inner, [:])
            if (innerRefusal) return innerRefusal
        }
        if (!refused) return null
        return "Darpan reads Shopify customer data at Level 1 only (no customer email, phone, name or address), so this query was not sent. It asks for: ${refused.join(', ')}"
    }

    static boolean isRefused(List<String> path) {
        if (!path) return false
        String last = path[-1]
        if (!last.startsWith("on:") && last in REFUSED_FIELDS) return true
        if (path.any { it.startsWith("on:") && it.substring(3) in REFUSED_TYPES }) return true
        // A field read directly on a customer must be one of the allowed ones; an inline fragment step is looked through.
        for (int i = 0; i < path.size() - 1; i++) {
            if (!(path[i] in CUSTOMER_STEPS)) continue
            String child = path.subList(i + 1, path.size()).find { !it.startsWith("on:") }
            if (child != null && !(child in CUSTOMER_FIELDS_ALLOWED)) return true
        }
        return false
    }
}
