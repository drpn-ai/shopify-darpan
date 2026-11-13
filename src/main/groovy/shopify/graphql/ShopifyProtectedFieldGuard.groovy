package shopify.graphql

import darpan.datasource.GraphQlDocumentCheck

/**
 * DAR-BE-260. Darpan reads Shopify customer data at protected-data Level 1 only: no customer email, phone, name or
 * address. Every Shopify call goes through ShopifyGraphqlTransport.execute, which asks this guard first, so a free-form
 * query (execute#ShopifyGraphql), an AI agent's query and a saved automation are held to the same line as the catalog.
 *
 * It checks a document's shape by field names, not against Shopify's schema, so it is written to refuse rather than
 * guess: one operation only; a query, or a mutation that is nothing but bulkOperationRunQuery (Darpan only reads —
 * a webhook or other mutation could send customer data elsewhere); every bulk job's inner query checked as a query;
 * every search string made only of key:value terms on keys that name no person; and anything it cannot read refused.
 * Pure: no Moqui, no network.
 */
class ShopifyProtectedFieldGuard {

    /** Fields that carry a person's email, phone or name, or a whole address, wherever they appear. */
    static final Set<String> REFUSED_FIELDS = [
            "email", "phone", "emailAddress", "phoneNumber", "contactEmail", "defaultEmailAddress", "defaultPhoneNumber",
            "firstName", "lastName", "displayName",
            "billingAddress", "shippingAddress", "defaultAddress", "addresses", "addressesV2", "displayAddress", "destination",
    ].asImmutable() as Set<String>

    /** Types that are an address, a contact point, or carry a cardholder's name; selecting into them is refused. */
    static final Set<String> REFUSED_TYPES = [
            "MailingAddress", "FulfillmentOrderDestination", "CustomerEmailAddress", "CustomerPhoneNumber", "CardPaymentDetails",
    ].asImmutable() as Set<String>

    /** Steps that lead to a customer: below one, only these may be read — who it is, never what it is called. */
    static final Set<String> CUSTOMER_STEPS = [
            "customer", "customers", "customerByIdentifier", "customerSegmentMembers", "on:Customer", "on:CustomerSegmentMember",
    ].asImmutable() as Set<String>
    static final Set<String> CUSTOMER_FIELDS_ALLOWED = ["id", "legacyResourceId", "__typename", "pageInfo", "cursor"].asImmutable() as Set<String>
    /** Connection wrappers looked through to reach the field actually read on a customer. */
    static final Set<String> CONNECTION_STEPS = ["edges", "node", "nodes"].asImmutable() as Set<String>

    /** The one mutation Darpan sends; its read travels as a string argument and is checked as a query of its own. */
    static final String BULK_RUN_QUERY = "bulkOperationRunQuery"

    /** Search keys that name or reach a person; a search on them could test whether an email or address exists. */
    static final Set<String> REFUSED_SEARCH_KEYS = [
            "email", "phone", "first_name", "last_name", "name", "customer_name", "default_address",
            "address", "address1", "address2", "city", "province", "province_code", "zip", "country", "country_code",
    ].asImmutable() as Set<String>
    static final List<String> REFUSED_SEARCH_KEY_PREFIXES = ["shipping_", "billing_", "address", "default_address"].asImmutable() as List<String>

    /** Null when the document may be sent; otherwise the reason it may not. */
    static String refusal(String document, Map variables = [:]) {
        return checkDocument(document, variables ?: [:], true)
    }

    private static String checkDocument(String document, Map variables, boolean bulkRunAllowed) {
        Map report = GraphQlDocumentCheck.fieldPaths(document)
        if (report.errors) return couldNotCheck(report.errors[0] as String)
        List<String> operations = (List<String>) report.operations
        if (operations.size() != 1) return couldNotCheck("it holds ${operations.size()} operations, and only one may be sent.")
        List<Map> fields = (List<Map>) report.fields
        List<Map> rootFields = fields.findAll { ((List) it.path).size() == 1 }
        if (operations[0] == "mutation") {
            List<String> others = rootFields.collect { ((List<String>) it.path)[0] }.findAll { it != BULK_RUN_QUERY || !bulkRunAllowed }
            if (others || !rootFields) return "Darpan only reads from Shopify, so this ${others ? others.unique().join(', ') : 'mutation'} was not sent."
        } else if (operations[0] != "query") {
            return "Darpan only reads from Shopify with queries, so this ${operations[0]} was not sent."
        }

        Set<String> refused = new TreeSet<String>()
        for (Map field : fields) {
            List<String> path = (List<String>) field.path
            Map<String, Map> arguments = (Map<String, Map>) field.arguments
            if (isRefused(path)) refused << label(path)
            if (path.size() == 1 && path[0] == BULK_RUN_QUERY) {
                // Every bulk job, however many are aliased into the document, is checked; one that can't be read is refused.
                String inner = stringArgument(arguments?.get("query"), variables)
                if (!inner) return couldNotCheck("a bulk job's query is missing or not a plain string.")
                String innerRefusal = checkDocument(inner, [:], false)
                if (innerRefusal) return innerRefusal
            } else if (arguments?.containsKey("query")) {
                Map arg = arguments.get("query")
                if (arg.kind == "other") return couldNotCheck("the search on ${label(path)} is not a plain string.")
                String search = stringArgument(arg, variables)
                String bad = search ? refusedSearchTerm(search) : null
                if (bad) return "Darpan reads Shopify customer data at Level 1 only, so a search on ${label(path)} by \"${bad}\" was not sent; searches use key:value terms on keys that name no person."
            }
        }
        if (!refused) return null
        return "Darpan reads Shopify customer data at Level 1 only (no customer email, phone, name or address), so this query was not sent. It asks for: ${refused.join(', ')}"
    }

    static boolean isRefused(List<String> path) {
        if (!path) return false
        String last = path[-1]
        if (!last.startsWith("on:") && last in REFUSED_FIELDS) return true
        if (path.any { it.startsWith("on:") && it.substring(3) in REFUSED_TYPES }) return true
        // A field read on a customer must be one of the allowed ones; fragments and connection wrappers are looked through.
        for (int i = 0; i < path.size() - 1; i++) {
            if (!(path[i] in CUSTOMER_STEPS)) continue
            String child = path.subList(i + 1, path.size()).find { !it.startsWith("on:") && !(it in CONNECTION_STEPS) }
            if (child != null && !(child in CUSTOMER_FIELDS_ALLOWED)) return true
        }
        return false
    }

    /**
     * The first term of a Shopify search string that is refused, or null. Every term must be key:value (a bare word is
     * matched against names and emails, so it is refused) on a key that names no person. AND, OR, NOT, a leading '-'
     * and parentheses are allowed; a quoted value is taken whole, so a key inside quotes is never read as a key.
     */
    static String refusedSearchTerm(String search) {
        String unquoted = search.replaceAll(/'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"/, "Q")
        for (String raw : unquoted.replace("(", " ").replace(")", " ").trim().split(/\s+/)) {
            String term = raw.startsWith("-") ? raw.substring(1) : raw
            if (!term || term in ["AND", "OR", "NOT"]) continue
            java.util.regex.Matcher m = term =~ /^([A-Za-z_][A-Za-z0-9_.]*):(.*)$/
            if (!m.matches()) return raw
            String key = m.group(1).toLowerCase(Locale.ROOT)
            if (key in REFUSED_SEARCH_KEYS || REFUSED_SEARCH_KEY_PREFIXES.any { key.startsWith(it) }) return raw
        }
        return null
    }

    private static String stringArgument(Map arg, Map variables) {
        if (arg?.kind == "string") return arg.value as String
        if (arg?.kind == "variable") {
            def value = variables?.get(arg.name)
            return value instanceof CharSequence ? value.toString() : null
        }
        return null
    }

    private static String label(List<String> path) {
        return path.findAll { !it.startsWith("on:") || it.substring(3) in REFUSED_TYPES }.join(".")
    }

    private static String couldNotCheck(String why) {
        return "Darpan could not check this Shopify query for customer data, so it was not sent: ${why}"
    }
}
