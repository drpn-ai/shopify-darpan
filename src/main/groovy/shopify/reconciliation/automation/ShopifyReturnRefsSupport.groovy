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
 * Per-REFUND Shopify records for returns reconciliation (DAR-BE-018, design §7). Originally this
 * class emitted one record per ORDER, carrying refundIds[]/returnIds[] id-set lists; the
 * 2026-08-17 returns-refund-grain-alignment plan (Task 1) reshaped it to one record per REFUND so
 * both sides of the compare share a grain — see the doc above toRecords for the full shape history
 * and the refund-return association investigation.
 *
 * CURSOR PATH, NOT BULK. refunds and returns are connections; ShopifyGraphqlQueryBuilder
 * .buildBulkQuery rejects connection-bearing fields because bulk JSONL emits their children as
 * separate __parentId lines and nothing here re-nests them. Cursor pagination returns naturally
 * nested objects — each order together with its own refunds and returns — which toRecords then
 * flattens into one output record per refund on that order.
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

        // Counts orders actually processed (one increment per edge, regardless of how many refund
        // records — zero, one, or several — that order goes on to produce). records.size() can no
        // longer stand in for this since Task 1's reshape (2026-08-17 grain-alignment plan): a
        // record is now per-REFUND, not per-order, so an order with two refunds contributes two
        // records and an order whose only event is an unrefunded return contributes zero.
        int ordersProcessed = 0

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
        // Shopify search net below and the client-side inWindow() filter in toRecords(); windowStart
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
                ordersProcessed++
                records.addAll(toRecords(node, refundsFirstEffective, returnsFirstEffective,
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
                        serverReportedOrderCount: ordersProcessed,
                        refundRecordCount       : records.size(),
                ]],
                warnings       : warnings,
                errors         : [],
        ]
    }

    /**
     * Builds this order's refund records — ONE PER REFUND, not one per order (DAR-BE-018;
     * 2026-08-17 returns-refund-grain-alignment plan, Task 1). Each emitted record is a flat scalar
     * map: { refundId, returnId, orderId, createdAt }, where createdAt is the REFUND's own creation
     * date. refundId/orderId/returnId are all bareId-normalized so they land in the same id space as
     * the OMS side's externalId (see CompareDatasetSupport.applyIdNormalizer's SHOPIFY_GID_TAIL).
     *
     * SHAPE HISTORY — read before "restoring" a list field: this method used to emit ONE RECORD PER
     * ORDER carrying refundIds[]/returnIds[] plus parallel refunds[]/returns[] {id, createdAt} lists
     * (see git history / the 2026-08-17 plan's "why"). That list shape itself replaced an even
     * earlier `refundsCreatedAt: {id: isoCreatedAt}` MAP keyed by data-derived refund ids (Important
     * #1, fix-wave-C): a plain (schema-inferring) Spark JSON read (ReconciliationServices.ingestFile)
     * turns a JSON object into a fixed-field StructType, never a MapType, so that map's field count
     * tracked the union of every refund/return id across the whole file, and
     * CompareDatasetSupport.buildJsonDataDf's `struct(col("*"))` carried that unbounded width
     * straight into the compare dataset. The per-order list shape fixed that by keeping a stable,
     * small set of field NAMES regardless of event count.
     *
     * Per-REFUND rows go further and satisfy the same constraint even more completely: there are no
     * list-valued fields left at all, so schema width cannot vary with event count even in principle.
     * The reason for THIS reshape is a different one, though — grain, not schema width: OMS
     * `reconciliationReturns` already emits one row per return whose externalId is a Shopify REFUND
     * id, so a Shopify side that emitted one row per ORDER could never be joined to it directly;
     * ReturnPresenceVerificationSupport (the other repo) exists only to bridge that grain mismatch
     * with bespoke presence-verification logic. Matching the grain — one row per refund on both sides
     * — turns that bespoke stage into an ordinary flat compare, the same shape orders reconciliation
     * already uses. See the 2026-08-17 plan doc for the full "why".
     *
     * refundIds/returnIds/refunds[]/returns[] are ALL REMOVED by this reshape. KNOWN BREAKAGE (not
     * this task's to fix): the other repo's ReturnPresenceVerificationSupport reads exactly those
     * four removed fields at multiple sites and will fail against this new shape until it is
     * retired or rewritten — tracked as a separate, later task in the same plan; do not "fix" it
     * from here without re-reading that class's own doc first (it has at least one behavior — a
     * per-order forward-match suppression — that is not a plain join and needs a deliberate call).
     *
     * REFUND -> RETURN ASSOCIATION (the one open design question Task 1 had to investigate rather
     * than assume): the GraphQL selection this class actually queries (ShopifySourceCatalog's
     * SHOPIFY_ORDER_RETURN_REFS fields: refunds.id, refunds.createdAt, returns.nodes.id,
     * returns.nodes.status, returns.nodes.createdAt — confirmed against both the field list and the
     * live-captured fixture, src/test/resources/fixtures/shopify-order-return-refs-response.json)
     * carries NO field on either side that names the other: a refund node has only {id, createdAt},
     * a return node only {id, status, createdAt}. There is therefore no direct link in what this
     * class actually receives today.
     *   Shopify's Admin GraphQL schema DOES publish a direct link — Refund.return (a nullable
     *   back-reference to the Return object; see shopify.dev's Refund object reference) — that would
     *   give an authoritative, unambiguous join with no pairing heuristic needed at all. It is
     *   deliberately NOT selected here: every other claim in this class about live Shopify behavior
     *   (the refunds/returns shape asymmetry, the no_return trim gap, the search/sort text) is backed
     *   by an actual probe against a real store on a real API version, and this field has not been.
     *   Selecting an unverified field on the live query is a materially bigger risk than a heuristic
     *   guess would be: GraphQL schema validation runs on the WHOLE document before any execution, so
     *   if `refunds.return.id` is not a valid path for a store's actual API version, the entire
     *   extraction fails closed for every order in that run — not just the returnId column. Adding
     *   and live-probing that field first, across every supported API version
     *   (SUPPORTED_API_VERSIONS), is a well-scoped, high-value follow-up; it must not be added
     *   speculatively from here.
     *   returnId is therefore UNCONDITIONALLY NULL in this class today — no pairing heuristic of any
     *   kind is applied. An earlier revision of this fix paired a refund with its order's return
     *   whenever the order carried EXACTLY ONE in-window return (reasoning that zero or several
     *   returns are genuinely ambiguous, so only the single-return case looked safe). Code review
     *   (fix round 1, 2026-08-18) correctly rejected that: an order can legitimately carry one real
     *   Return AND a separate refund that has nothing to do with it — a goodwill refund, a shipping
     *   refund, a price adjustment — all ordinary Shopify patterns. The single-return case is not
     *   evidence of relatedness, only of order-level coincidence, and pairing on it manufactures a
     *   CONFIDENT LIE: a returnId that looks authoritative but is not, which produces a clean-looking
     *   diff that is wrong — precisely the failure this whole plan exists to remove. A null returnId
     *   is a known unknown and is the honest, harmless answer until the real Refund.return link is
     *   live-verified and wired in; do not reintroduce any form of order-level pairing without that
     *   verification.
     *
     * NO-REFUND-YET NARROWING (deliberate, not a defect — see the plan's "Known narrowing" section):
     * a refund-driven extract cannot emit a record for a return that has no refund yet. When this
     * order's in-window returns are non-empty but its in-window refunds are empty, no record is
     * emitted for it and a warning is added instead, so the gap is counted and surfaced rather than
     * silently dropped. This also retires the old "empty order as reverse-pass evidence" behavior: an
     * order with neither refunds nor returns in window now simply contributes nothing, with no
     * warning either — that evidence existed only to support ReturnPresenceVerificationSupport's own
     * per-order lookup, which a flat, matching-grain join does not need.
     */
    private static List<Map<String, Object>> toRecords(Map node, int refundsFirst, int returnsFirst,
                                                        long floorMillis, long windowEndMillis,
                                                        List<String> warnings) {
        String orderId = bareId(node.get("legacyResourceId") ?: node.get("id"))
        List<Map<String, String>> refundEvents = collectEvents(node.get("refunds"), orderId, "refunds", refundsFirst, warnings)
        List<Map<String, String>> returnEvents = collectEvents(node.get("returns"), orderId, "returns", returnsFirst, warnings)

        List<Map<String, String>> inWindowRefunds = inWindow(refundEvents, orderId, "refund", floorMillis, windowEndMillis, warnings)
        List<Map<String, String>> inWindowReturns = inWindow(returnEvents, orderId, "return", floorMillis, windowEndMillis, warnings)

        if (inWindowRefunds.isEmpty()) {
            if (!inWindowReturns.isEmpty()) {
                warnings.add(("Order ${orderId} has ${inWindowReturns.size()} return(s) in this window with no " +
                        "matching refund; a refund-driven extract cannot represent a return before it is " +
                        "refunded, so no record was emitted for it.").toString())
            }
            return []
        }

        // returnId is UNCONDITIONALLY NULL — see the ASSOCIATION section of this method's doc above
        // (fix round 1, 2026-08-18). inWindowReturns is still collected and windowed above only to
        // drive the no-refund-yet warning; it must NEVER be read here to guess a returnId, by any
        // heuristic, no matter how narrow — a wrong returnId is worse than a missing one.
        return inWindowRefunds.collect { Map<String, String> refund ->
            [
                    refundId : refund.id,
                    returnId : null,
                    orderId  : orderId,
                    createdAt: refund.createdAt,
            ] as Map<String, Object>
        }
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
     * caller (toRecords) applies the window filter afterward via inWindow().
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
