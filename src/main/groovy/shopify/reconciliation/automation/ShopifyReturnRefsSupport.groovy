package shopify.reconciliation.automation

import shopify.graphql.ShopifyGraphqlQueryBuilder
import shopify.graphql.ShopifyGraphqlTransport
import shopify.graphql.ShopifySourceCatalog

import java.time.Instant
import java.util.regex.Matcher
import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

/**
 * Per-order Shopify refund ids and return ids for returns reconciliation (DAR-BE-018, design §7).
 *
 * CURSOR PATH, NOT BULK. refunds and returns are connections; ShopifyGraphqlQueryBuilder
 * .buildBulkQuery rejects connection-bearing fields because bulk JSONL emits their children as
 * separate __parentId lines and nothing here re-nests them. Cursor pagination returns naturally
 * nested objects, which is exactly the per-order id-set shape the match rule wants.
 *
 * Ids are emitted BARE (GID tail). The OMS side stores a bare numeric Shopify reference, and the
 * SHOPIFY_GID_TAIL normalizer's type segment is a wildcard, so both sides land in the same space
 * regardless of whether the id names a Refund or a Return (design §2).
 *
 * WINDOW SEMANTICS (product-owner-verified 2026-08-13, replacing this class's original defect):
 * returns reconciliation checks the CREATION of the return/refund EVENT, not any order-level date.
 * Orders carrying a refund or return can be arbitrarily old, so this cannot window on order-level
 * created_at the way ShopifyOrdersSupport's order extract does — that put the two reconciliation
 * sides on near-disjoint populations (returns lag their order by days to weeks).
 *
 * Precedent: ShopifyExchangeSweepSupport windows its net on order updated_at with NO upper bound —
 * any return/refund activity bumps updated_at, but later, unrelated activity on the SAME order must
 * not hide an earlier in-window event, so there is deliberately no upper bound to bump past. The
 * actual date is filtered client-side from the event's own createdAt. This class follows the same
 * shape: the Shopify search below is a wide, lower-bound-only NET, and an authoritative
 * [floor, windowEnd) filter is applied here, in Groovy, against each refund's and each return's own
 * createdAt (see collectEvents/inWindow) — floor is windowStart WIDENED BACK by a lookback; see the
 * LOOKBACK section below for why the plain windowStart is not itself the floor.
 *
 * The net's trim is intentionally NOT `-return_status:no_return` alone: live probing (gorjana-
 * sandbox.myshopify.com, Admin API 2026-01, 2026-08-13, HTTP 200) showed that filter alone returns
 * only orders whose return_status has left NO_RETURN, and a refund issued with no Return object
 * attached (a refund-only order) never moves return_status — the filter silently drops the entire
 * refund-only population, gutting the refund match spine. The OR-widened trim below adds
 * financial_status refunded/partially_refunded so refund-only orders survive.
 *
 * LOOKBACK (Important #3, fix-wave-C re-review): the design's own measured fact (RQ-23, cited in
 * ReturnPresenceVerificationSupport's class doc) is that OMS lags Shopify by ~38 minutes. An OMS
 * return whose entryDate sits just inside windowStart can therefore point at a Shopify refund
 * created just BEFORE windowStart — with a plain [windowStart, windowEnd) client-side filter that
 * refund would never be fetched at all, the forward match would fail, and the reverse grace would
 * not rescue it either (it compares entryDate against now - graceHours, which is false for any run
 * made more than graceHours after windowStart — i.e. every normal daily run and every backfill).
 * That manufactures a recurring false missing-in-Shopify cohort, roughly one sync-lag wide, at the
 * start of every window. Fix: both the Shopify search NET and the client-side inWindow() filter use
 * a floor of [windowStart - lookback, windowEnd) instead of [windowStart, windowEnd) — lookbackHours
 * defaults to DEFAULT_LOOKBACK_HOURS (3h, matching ReturnPresenceVerificationSupport's own grace
 * default) and is threaded from the extract service as an option, not a bare literal. windowEnd is
 * unaffected — there was never an upper bound on the net to begin with (see above).
 *
 * The REPORTING window itself ([windowStart, windowEnd), unwidened) is not this class's concern: the
 * consumer (ReturnPresenceVerificationSupport) gates its reverse (missing-in-OMS) pass on the plain
 * windowStart its own caller already has, so a pre-window event fetched only because of this
 * lookback is available for forward matching but never independently reported missing-in-OMS.
 */
class ShopifyReturnRefsSupport {

    static final int DEFAULT_CONNECTION_PAGE_SIZE = 50
    static final int MAX_PAGE_COUNT = 20000
    // Important #3: matches ReturnPresenceVerificationSupport.DEFAULT_GRACE_HOURS in the other repo.
    // Keep the two in sync if either default ever moves — they encode the same RQ-23 sync-lag
    // assumption from opposite ends of the pipeline.
    static final int DEFAULT_LOOKBACK_HOURS = 3

    private static final Pattern GID_TAIL = Pattern.compile('gid://shopify/[^/]+/(\\d+)(?:\\?.*)?$')
    private static final Pattern TRAILING_DIGITS = Pattern.compile('(\\d+)$')

    /** Mirrors CompareDatasetSupport.applyIdNormalizer's SHOPIFY_GID_TAIL so both sides agree. */
    static String bareId(Object rawId) {
        String value = normalize(rawId)
        if (!value) return null
        Matcher gidMatcher = GID_TAIL.matcher(value)
        if (gidMatcher.find()) return gidMatcher.group(1)
        Matcher digitsMatcher = TRAILING_DIGITS.matcher(value)
        if (digitsMatcher.find()) return digitsMatcher.group(1)
        return value
    }

    static Map<String, Object> extractReturnRefs(Map authConfig, Object windowStart, Object windowEnd,
                                                 Map options = [:], Closure executor = null) {
        List<String> errors = []
        List<String> warnings = []
        List<Map<String, Object>> records = []

        Closure exec = executor ?: { Map cfg, String queryDocument, Map variables, Map opts ->
            ShopifyGraphqlTransport.execute(cfg, queryDocument, variables, opts)
        }

        Map<String, Object> built
        try {
            built = ShopifyGraphqlQueryBuilder.buildQuery([
                    sourceDefinitionId  : ShopifySourceCatalog.SHOPIFY_ORDER_RETURN_REFS,
                    operationName       : "DarpanShopifyReturnRefsByDateWindow",
                    // Only a LOWER bound is ever sent to Shopify — see the class doc. updatedAtFrom
                    // both validates windowStart (buildQuery throws a clear error for garbage input)
                    // and drives resolveSortKey to UPDATED_AT via that filter's own definition.
                    filters             : [updatedAtFrom: normalize(windowStart)],
                    pageSize            : options?.pageSize,
                    connectionPageSizes : [
                            refundsFirst: normalizeInt(options?.connectionPageSize, DEFAULT_CONNECTION_PAGE_SIZE),
                            returnsFirst: normalizeInt(options?.connectionPageSize, DEFAULT_CONNECTION_PAGE_SIZE),
                    ],
            ])
        } catch (Exception e) {
            return [records: [], recordCount: 0, dataAvailable: false, requestMetadata: [:],
                    warnings: warnings, errors: [normalize(e.message) ?: "Shopify return-refs query could not be built."]]
        }

        // built.filters.updatedAtFrom is the CANONICAL Instant.toString() form buildQuery's own
        // multi-format datetime normalizer produced — reuse it (rather than re-deriving from the
        // raw windowStart) so the search text sent to Shopify and the client-side window floor
        // below agree on the exact same instant.
        String windowStartIso = normalize(((Map) built.filters)?.get("updatedAtFrom"))
        Long windowStartMillis = parseIsoMillis(windowStartIso)
        Long windowEndMillis = parseIsoMillis(windowEnd)
        if (windowStartMillis == null || windowEndMillis == null) {
            return [records: [], recordCount: 0, dataAvailable: false, requestMetadata: [:],
                    warnings: warnings,
                    errors: ["Shopify return-refs windowEnd must be an ISO-8601 date-time."]]
        }

        // Important #3: widen the fetch/emit floor by lookbackHours (default DEFAULT_LOOKBACK_HOURS)
        // BEFORE windowStart — see the class doc's LOOKBACK section. This floor drives both the
        // Shopify search net below and the client-side inWindow() filter in toRecord(); windowStart
        // itself (unwidened) plays no further role in this class — the reporting-window gate lives
        // downstream, in the OMS-side consumer, keyed off its own caller's plain windowStart.
        int lookbackHours = normalizeInt(options?.lookbackHours, DEFAULT_LOOKBACK_HOURS)
        long netFloorMillis = windowStartMillis - (lookbackHours * 3600_000L)
        String netFloorIso = Instant.ofEpochMilli(netFloorMillis).toString()

        // The Shopify-side NET (live-verified 2026-08-13, HTTP 200): a wide, lower-bound-only search
        // — see the class doc for why a plain `-return_status:no_return` trim is wrong. The
        // authoritative window filter is applied client-side below, against each event's own
        // createdAt, never against this net.
        ((Map) built.variables).put("query", ("updated_at:>='${netFloorIso}' AND " +
                "((-return_status:no_return) OR (financial_status:refunded) OR (financial_status:partially_refunded))").toString())

        String queryDocument = built.queryDocument as String
        // buildQuery already resolves first/after/query/reverse/<root>First into one variables map
        // consistent with the rendered document's declared variables ($reverse, $refundsFirst and
        // $returnsFirst are all non-null!). Each page must start from a copy of it and only touch
        // "after" — rebuilding a fresh {first, after} map from scratch would silently drop $query
        // (the window filter itself), $reverse, $refundsFirst and $returnsFirst, and Shopify would
        // reject every request at GraphQL variable-validation time, before the search query is even
        // evaluated.
        Map<String, Object> baseVariables = (Map<String, Object>) built.variables
        // Read the CLAMPED per-connection page sizes back off the built query rather than off raw
        // options: ShopifyGraphqlQueryBuilder clamps refundsFirst/returnsFirst to the catalog's
        // connectionMaxPageSize (100). An unclamped options.connectionPageSize (e.g. 200) would make
        // the saturation heuristic below warn only at >=200 while Shopify itself truncates at 100 —
        // detection would switch off exactly when truncation begins.
        int refundsFirstEffective = normalizeInt(baseVariables.get("refundsFirst"), DEFAULT_CONNECTION_PAGE_SIZE)
        int returnsFirstEffective = normalizeInt(baseVariables.get("returnsFirst"), DEFAULT_CONNECTION_PAGE_SIZE)
        String cursor = null
        int pageCount = 0
        boolean hasNextPage = true

        while (hasNextPage) {
            if (pageCount++ >= MAX_PAGE_COUNT) {
                warnings.add("Return-refs extraction stopped at the ${MAX_PAGE_COUNT}-page ceiling; the window may be incomplete.".toString())
                break
            }
            Map<String, Object> variables = new LinkedHashMap<>(baseVariables)
            if (cursor) variables.put("after", cursor)

            Map response = (Map) exec(authConfig, queryDocument, variables, options ?: [:])
            if (response?.ok != true) {
                errors.addAll(((List) (response?.errors ?: ["Shopify return-refs request failed."]))
                        .collect { normalize(it) }.findAll { it })
                break
            }

            Map ordersConnection = (Map) walk(response, ["data", "orders"])
            if (ordersConnection == null) {
                errors.add("Shopify return-refs response had no orders connection.")
                break
            }

            List edges = (ordersConnection.get("edges") instanceof List) ? (List) ordersConnection.get("edges") : []
            edges.each { Object rawEdge ->
                Map node = (Map) ((Map) rawEdge)?.get("node")
                if (node == null) return
                records.add(toRecord(node, refundsFirstEffective, returnsFirstEffective,
                        netFloorMillis, windowEndMillis, warnings))
            }

            Map pageInfo = (Map) ordersConnection.get("pageInfo")
            hasNextPage = pageInfo?.get("hasNextPage") == true
            cursor = normalize(pageInfo?.get("endCursor"))
            if (hasNextPage && !cursor) {
                warnings.add("Shopify reported another page but returned no cursor; extraction stopped early.")
                break
            }
        }

        if (errors) {
            return [records: [], recordCount: 0, dataAvailable: false, requestMetadata: [:],
                    warnings: warnings, errors: errors]
        }

        return [
                records        : records,
                recordCount    : records.size(),
                dataAvailable  : !records.isEmpty(),
                requestMetadata: [filters: [
                        serverReportedOrderCount: records.size(),
                        refundIdCount           : records.sum { ((List) it.refundIds).size() } ?: 0,
                        returnIdCount           : records.sum { ((List) it.returnIds).size() } ?: 0,
                ]],
                warnings       : warnings,
                errors         : [],
        ]
    }

    /**
     * Builds one order's record. refundIds/returnIds and the parallel refunds/returns lists are
     * already filtered to events whose OWN createdAt falls inside [floorMillis, windowEndMillis) —
     * see the class doc's LOOKBACK section (floorMillis is windowStart minus the lookback, not the
     * plain reporting windowStart). An order can legitimately appear here with every set empty: it
     * proves Shopify has nothing IN THIS (widened) WINDOW to match, which is itself evidence for the
     * reverse pass (an OMS return pointing at it really is missing).
     *
     * refunds / returns are REQUIRED downstream, not decorative: ReturnPresenceVerificationSupport's
     * reverse pass needs to key its grace check on each refund's OWN createdAt (not the order's), so
     * a refund minted five minutes ago on a three-month-old order still reads as young rather than
     * permanently "old". Important #1 (fix-wave-C re-review): this used to be a
     * `refundsCreatedAt: {id: isoCreatedAt}` MAP keyed by data-derived refund ids — a plain
     * (schema-inferring) Spark JSON read (ReconciliationServices.ingestFile) turns a JSON object into
     * a fixed-field StructType, never a MapType, so that map's field count tracked the union of every
     * refund/return id across the whole file; CompareDatasetSupport.buildJsonDataDf's
     * `struct(col("*"))` then carried that width straight into the compare dataset — fine for a small
     * daily window, thousands of nested fields for a large merchant or a backfill. A list of objects
     * with stable field names has a fixed, small schema regardless of event count. Exact record shape
     * (consumed by the other repo's fix wave — do not rename without a migration plan):
     *   { orderId, orderName, createdAt, refundIds: [...], returnIds: [...],
     *     refunds: [{id, createdAt}, ...], returns: [{id, createdAt}, ...] }
     * returns[] (parallel to returnIds) is consumed nowhere today — kept for symmetry and future use
     * now that a list costs nothing extra, same as before this fix.
     */
    private static Map<String, Object> toRecord(Map node, int refundsFirst, int returnsFirst,
                                                long floorMillis, long windowEndMillis,
                                                List<String> warnings) {
        String orderId = bareId(node.get("legacyResourceId") ?: node.get("id"))
        List<Map<String, String>> refundEvents = collectEvents(node.get("refunds"), orderId, "refunds", refundsFirst, warnings)
        List<Map<String, String>> returnEvents = collectEvents(node.get("returns"), orderId, "returns", returnsFirst, warnings)

        List<Map<String, String>> inWindowRefunds = inWindow(refundEvents, orderId, "refund", floorMillis, windowEndMillis, warnings)
        List<Map<String, String>> inWindowReturns = inWindow(returnEvents, orderId, "return", floorMillis, windowEndMillis, warnings)

        return [
                orderId  : orderId,
                orderName: normalize(node.get("name")),
                createdAt: normalize(node.get("createdAt")),
                refundIds: inWindowRefunds.collect { it.id },
                returnIds: inWindowReturns.collect { it.id },
                refunds  : inWindowRefunds.collect { [id: it.id, createdAt: it.createdAt] },
                returns  : inWindowReturns.collect { [id: it.id, createdAt: it.createdAt] },
        ]
    }

    /**
     * Handles BOTH live shapes, which are asymmetric (probed 2026-08-13, API 2026-01):
     *   refunds -> a plain List of objects, no wrapper and NO pageInfo
     *   returns -> a ReturnConnection: {nodes: [...], pageInfo: {...}} in the schema — but in
     *              practice this repo's renderQueryDocument only ever emits `pageInfo` for the ROOT
     *              orders connection, never for a nested one, so live traffic never actually carries
     *              it on returns either. The edges/node branch and the pageInfo check below are kept
     *              defensively (a schema or query-shape change degrades to a parsed set rather than
     *              a silently empty one) but are never the ONLY truncation signal.
     *
     * TRUNCATION is the real hazard here: an understated id set manufactures false
     * missing-in-Shopify diffs, which is precisely what this extractor exists to prevent. Detection
     * therefore applies the SATURATION heuristic (returned size >= requested first) to BOTH shapes —
     * not just the plain list. It over-warns on an order holding exactly `first` items; that false
     * positive is far cheaper than a silently truncated set.
     *
     * Returns every event this page reported (id + own createdAt), UNFILTERED by window — truncation
     * must be judged against everything Shopify actually returned, before any window trim. The
     * caller (toRecord) applies the window filter afterward via inWindow().
     */
    private static List<Map<String, String>> collectEvents(Object rawConnection, String orderId, String label,
                                                            int requestedFirst, List<String> warnings) {
        if (rawConnection == null) return []
        List rawNodes = []
        boolean maybeTruncated = false
        if (rawConnection instanceof List) {
            rawNodes = (List) rawConnection
            maybeTruncated = requestedFirst > 0 && rawNodes.size() >= requestedFirst
        } else if (rawConnection instanceof Map) {
            Map connection = (Map) rawConnection
            if (connection.get("nodes") instanceof List) {
                rawNodes = (List) connection.get("nodes")
            } else if (connection.get("edges") instanceof List) {
                rawNodes = ((List) connection.get("edges")).collect { ((Map) it)?.get("node") }
            }
            boolean pageInfoHasNextPage = ((Map) (connection.get("pageInfo") ?: [:])).get("hasNextPage") == true
            boolean saturatedFirst = requestedFirst > 0 && rawNodes.size() >= requestedFirst
            maybeTruncated = pageInfoHasNextPage || saturatedFirst
        }
        if (maybeTruncated) {
            warnings.add("Order ${orderId} returned ${rawNodes.size()} ${label}, the maximum requested — the list may be truncated and diffs for it may be wrong.".toString())
        }
        return rawNodes.findAll { it instanceof Map }.collect { Map rawNode ->
            [id: bareId(rawNode.get("id")), createdAt: normalize(rawNode.get("createdAt"))]
        }.findAll { Map event -> event.id } as List<Map<String, String>>
    }

    /**
     * Keeps only events whose OWN createdAt falls inside the half-open [floorMillis, windowEndMillis)
     * range — the client-side filter the window-semantics fix requires; the Shopify search is only a
     * candidate net (see class doc). floorMillis is windowStart minus the Important #3 lookback, not
     * the plain reporting windowStart — see the class doc's LOOKBACK section. An event with a missing
     * or unparseable createdAt is KEPT rather than silently dropped (with a warning): dropping it
     * would manufacture exactly the false missing-in-Shopify diff this extractor exists to prevent,
     * while an over-inclusive candidate is, at worst, resolved as a forward match downstream.
     */
    private static List<Map<String, String>> inWindow(List<Map<String, String>> events, String orderId, String label,
                                                       long floorMillis, long windowEndMillis,
                                                       List<String> warnings) {
        return events.findAll { Map event ->
            Long createdAtMillis = parseIsoMillis(event.createdAt as String)
            if (createdAtMillis == null) {
                warnings.add("Order ${orderId} ${label} ${event.id} has no parseable createdAt; keeping it rather than silently excluding it from the window.".toString())
                return true
            }
            return createdAtMillis >= floorMillis && createdAtMillis < windowEndMillis
        }
    }

    private static Long parseIsoMillis(Object rawValue) {
        String text = normalize(rawValue)
        if (!text) return null
        try {
            return Instant.parse(text).toEpochMilli()
        } catch (Exception ignored) {
            return null
        }
    }

    private static Object walk(Object root, List<String> path) {
        Object current = root
        for (String segment : path) {
            if (!(current instanceof Map)) return null
            current = ((Map) current).get(segment)
        }
        return current
    }
}
