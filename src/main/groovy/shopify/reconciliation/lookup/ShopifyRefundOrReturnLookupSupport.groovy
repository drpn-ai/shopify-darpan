package shopify.reconciliation.lookup

import darpan.common.ValueSupport
import shopify.graphql.ShopifyGraphqlTransport

import java.util.regex.Pattern

/**
 * Point-existence check for Shopify refund/return ids against the primary datastore via nodes(ids:).
 *
 * Sibling of {@link ShopifyOrderLookupSupport}, and it exists for a sharper version of the same
 * problem. The returns extract enumerates through {@code orders(query:)} because the Admin API has no
 * top-level refunds or returns connection — both exist only as singular by-id lookups and as
 * connections nested under an order. That order-scoped net is served by Shopify's search index and
 * was observed (gorjana prod, 2026-08-18) to return only recent orders, so an OLD order carrying a
 * BRAND-NEW refund never enters the extract and its OMS counterpart is reported missing-in-Shopify
 * even though the refund plainly exists. nodes(ids:) resolves by id directly and is not subject to
 * that gap, which is exactly what the verification pass needs.
 *
 * TYPE IS UNKNOWN, BY DESIGN. OMS stores a bare numeric Shopify reference that may name a Refund OR a
 * Return, and the diff row carries only that id — never a type tag. So each bare id is looked up under
 * BOTH gid forms and counted present if either resolves. Inferring the type from digit length would
 * work on today's data purely by coincidence of current id ranges (returns 11 digits, refunds 12) and
 * would fail silently as ids grow. The verification pass only ever needs existence, never type, so
 * asking both questions at once is both cheaper and safer than guessing.
 */
class ShopifyRefundOrReturnLookupSupport {
    /** Shopify caps nodes(ids:) at 250 ids per call. */
    static final int MAX_GIDS_PER_QUERY = 250
    /** A bare id costs two gids, so the per-call id budget is half the gid cap. Chunking on IDS (not
     *  gids) is what guarantees an id's two forms never straddle a call boundary — if they did, one
     *  failing call could strand a half-answer and the id would be judged on incomplete evidence. */
    static final int MAX_IDS_PER_QUERY = MAX_GIDS_PER_QUERY.intdiv(2)
    static final String REFUND_GID_PREFIX = "gid://shopify/Refund/"
    static final String RETURN_GID_PREFIX = "gid://shopify/Return/"
    static final String NODES_QUERY = "query refundOrReturnIdLookup(\$ids: [ID!]!) { nodes(ids: \$ids) { id } }"

    private static final Pattern BARE_NUMERIC_ID = Pattern.compile(/^\d+$/)

    /**
     * Returns [ok, foundIds, missingIds, errors]. Ids are echoed back in their requested form. On any
     * transport failure ok=false and BOTH id lists stay empty — a failed lookup must never classify an
     * id as missing. Ids that cannot name a Shopify object (anything neither bare-numeric nor already
     * a gid) are reported missing WITHOUT being sent: they can never resolve, and the conservative
     * outcome is that their diff row stays reported.
     */
    static Map<String, Object> lookupRefundOrReturnIds(Map authConfig, List<Object> refundOrReturnIds, Map options = [:]) {
        List<String> requested = (refundOrReturnIds ?: []).collect { ValueSupport.normalize(it) }.findAll { it } as List<String>
        if (!requested) return [ok: true, foundIds: [], missingIds: [], errors: []]

        Map<String, List<String>> gidFormsByRequested = new LinkedHashMap<>()
        requested.each { String rawId ->
            if (rawId.startsWith("gid://")) gidFormsByRequested.put(rawId, [rawId])
            else if (BARE_NUMERIC_ID.matcher(rawId).matches()) {
                gidFormsByRequested.put(rawId, [REFUND_GID_PREFIX + rawId, RETURN_GID_PREFIX + rawId])
            } else {
                gidFormsByRequested.put(rawId, [])
            }
        }

        List<String> queryable = gidFormsByRequested.findAll { !it.value.isEmpty() }*.key as List<String>
        List<String> errors = []
        Set<String> foundGids = new LinkedHashSet<>()
        queryable.collate(MAX_IDS_PER_QUERY).each { List<String> idChunk ->
            if (errors) return
            List<String> gidChunk = idChunk.collectMany { gidFormsByRequested.get(it) } as List<String>
            Map<String, Object> result = ShopifyGraphqlTransport.execute(authConfig, NODES_QUERY, [ids: gidChunk], options ?: [:])
            if (!result.ok) {
                List resultErrors = result.errors instanceof List ? (List) result.errors : []
                errors.addAll(resultErrors ? resultErrors.collect { it?.toString() } : ["Shopify refund/return lookup failed."])
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
        gidFormsByRequested.each { String rawId, List<String> forms ->
            (forms.any { foundGids.contains(it) } ? foundIds : missingIds).add(rawId)
        }
        return [ok: true, foundIds: foundIds, missingIds: missingIds, errors: []]
    }
}
