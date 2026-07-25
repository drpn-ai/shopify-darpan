package shopify.reconciliation.lookup

import darpan.common.ValueSupport
import shopify.graphql.ShopifyGraphqlTransport

/**
 * Point-existence check for Shopify orders against the primary datastore via nodes(ids:).
 *
 * The bulk extraction's orders(query:) filter is served by Shopify's search index, which can omit a
 * small number of real orders; the reconciliation verification pass calls this to confirm or refute
 * "missing in SHOPIFY" differences. nodes(ids:) resolves by id directly and is not subject to the
 * search-index gap.
 */
class ShopifyOrderLookupSupport {
    /** Shopify caps nodes(ids:) at 250 ids per call. */
    static final int MAX_IDS_PER_QUERY = 250
    static final String ORDER_GID_PREFIX = "gid://shopify/Order/"
    static final String NODES_QUERY = "query orderIdLookup(\$ids: [ID!]!) { nodes(ids: \$ids) { id } }"

    /**
     * Returns [ok, foundIds, missingIds, errors]. Ids are echoed back in their requested form
     * (numeric or gid). On any transport failure ok=false and BOTH id lists stay empty — a failed
     * lookup must never classify an order as missing.
     */
    static Map<String, Object> lookupOrderIds(Map authConfig, List<Object> orderIds, Map options = [:]) {
        List<String> requested = (orderIds ?: []).collect { ValueSupport.normalize(it) }.findAll { it } as List<String>
        if (!requested) return [ok: true, foundIds: [], missingIds: [], errors: []]

        Map<String, String> gidByRequested = new LinkedHashMap<>()
        requested.each { String rawId ->
            gidByRequested.put(rawId, rawId.startsWith("gid://") ? rawId : (ORDER_GID_PREFIX + rawId))
        }

        List<String> errors = []
        Set<String> foundGids = new LinkedHashSet<>()
        gidByRequested.values().toList().collate(MAX_IDS_PER_QUERY).each { List<String> chunk ->
            if (errors) return
            Map<String, Object> result = ShopifyGraphqlTransport.execute(authConfig, NODES_QUERY, [ids: chunk], options ?: [:])
            if (!result.ok) {
                List resultErrors = result.errors instanceof List ? (List) result.errors : []
                errors.addAll(resultErrors ? resultErrors.collect { it?.toString() } : ["Shopify order lookup failed."])
                return
            }
            Object nodes = result.data instanceof Map ? ((Map) result.data).nodes : null
            (nodes instanceof List ? (List) nodes : []).each { Object node ->
                String gid = node instanceof Map ? ValueSupport.normalize(((Map) node).id) : null
                if (gid) foundGids.add(gid)
            }
        }
        if (errors) return [ok: false, foundIds: [], missingIds: [], errors: errors]

        List<String> foundIds = []
        List<String> missingIds = []
        gidByRequested.each { String rawId, String gid ->
            (foundGids.contains(gid) ? foundIds : missingIds).add(rawId)
        }
        return [ok: true, foundIds: foundIds, missingIds: missingIds, errors: []]
    }
}
