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
 * Per-EVENT Shopify records for returns reconciliation (DAR-BE-018, design §7). An event is a
 * refund OR a return; every in-window refund and every in-window return each emit their own row,
 * tagged with an eventType. This is this class's SECOND reshape: it originally emitted one record
 * per ORDER carrying refundIds[]/returnIds[] id-set lists, then (2026-08-17 plan, Task 1) one
 * record per REFUND with a nullable returnId, and now (REVISION 2026-08-18, same plan) one record
 * per EVENT with no refund/return distinction beyond the eventType tag — see the doc above
 * toRecords for the full shape history and why each reshape happened.
 *
 * CURSOR PATH, NOT BULK. refunds and returns are connections; ShopifyGraphqlQueryBuilder
 * .buildBulkQuery rejects connection-bearing fields because bulk JSONL emits their children as
 * separate __parentId lines and nothing here re-nests them. Cursor pagination returns naturally
 * nested objects — each order together with its own refunds and returns — which toRecords then
 * flattens into one output record per EVENT (refund or return) on that order.
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
    // REVISION 2026-08-18: the eventType tag on the per-event record shape. See the doc above
    // toRecords for why this replaced the earlier refundId/returnId column split.
    static final String EVENT_TYPE_REFUND = "REFUND"
    static final String EVENT_TYPE_RETURN = "RETURN"

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

        // Counts orders actually processed (one increment per edge, regardless of how many event
        // records — zero, one, or several — that order goes on to produce). records.size() can no
        // longer stand in for this since this class's per-order shape was reshaped (2026-08-17
        // grain-alignment plan): a record is now per-EVENT (one refund OR one return), so an order
        // with two refunds and one return contributes three records to a single ordersProcessed++.
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
                        eventRecordCount        : records.size(),
                ]],
                warnings       : warnings,
                errors         : [],
        ]
    }

    /**
     * Builds this order's EVENT records — one row per refund AND one row per return, each tagged
     * with its own eventType (DAR-BE-018; 2026-08-17 returns-refund-grain-alignment plan, Task 1,
     * REVISION 2026-08-18). Each emitted record is a flat scalar map:
     *   { eventId, eventType: EVENT_TYPE_REFUND | EVENT_TYPE_RETURN, orderId, createdAt }
     * createdAt is that SPECIFIC event's own creation date (a refund row carries the refund's
     * createdAt; a return row carries the return's). eventId/orderId are bareId-normalized so they
     * land in the same id space as the OMS side's externalId (see
     * CompareDatasetSupport.applyIdNormalizer's SHOPIFY_GID_TAIL).
     *
     * WHY ONE KEY, NO PRECEDENCE RULE (the reason for this revision): OMS
     * reconciliationReturns.externalId is *usually* a Shopify refund id, but the product owner
     * confirmed (2026-08-18) that for a small number of returns it holds the RETURN id instead —
     * exactly the ambiguity the now-retired-in-spirit ReturnPresenceVerificationSupport hedged on by
     * checking refund ids first and falling back to return ids (that class's forward pass, the other
     * repo's ReturnPresenceVerificationSupport.groovy:121-122). A Shopify side that only ever emitted
     * refunds had no equivalent fallback: an OMS row whose externalId happens to be a return id would
     * join against nothing, miss every refund on its order, and report missing-in-Shopify — a SILENT
     * FALSE POSITIVE, exactly the failure class this whole plan exists to remove. Emitting both
     * refunds and returns as same-shaped rows under one eventId column removes the need for any
     * precedence rule entirely: whichever kind of id OMS actually put in externalId, the matching
     * Shopify event lands in the same eventId column on this side and joins directly, no lookup order
     * required.
     *
     * SHAPE HISTORY — read before "restoring" a removed field: this class has now been reshaped
     * TWICE. It originally emitted ONE RECORD PER ORDER carrying refundIds[]/returnIds[] plus
     * parallel refunds[]/returns[] {id, createdAt} lists (see git history). The 2026-08-17 plan's
     * Task 1 first reshaped that to ONE RECORD PER REFUND — {refundId, returnId, orderId, createdAt}
     * — to match OMS's per-return grain; this REVISION (2026-08-18) reshapes it again, to one record
     * per EVENT of either kind, once the per-refund shape's OMS-id assumption above turned out not to
     * hold universally. The very first, pre-list shape was itself a `refundsCreatedAt: {id:
     * isoCreatedAt}` MAP keyed by data-derived refund ids (Important #1, fix-wave-C): a plain
     * (schema-inferring) Spark JSON read (ReconciliationServices.ingestFile) turns a JSON object into
     * a fixed-field StructType, never a MapType, so that map's field count tracked the union of every
     * refund/return id across the whole file, and CompareDatasetSupport.buildJsonDataDf's
     * `struct(col("*"))` carried that unbounded width straight into the compare dataset. Every shape
     * since has kept a small, event-count-independent set of field NAMES; per-event rows keep that
     * property too — there are still no list-valued fields anywhere in the output.
     *
     * WHAT THIS REVISION REMOVES relative to the per-refund shape it supersedes:
     *   - refundId/returnId as two separate columns on one row. Refunds and returns are now separate
     *     ROWS instead of one row trying to carry both, so there is no longer a refund->return
     *     association to get right (or wrong) at all — see ASSOCIATION HISTORY below for why that
     *     entire problem is now moot, kept here because it documents real investigation and a real,
     *     reviewed rejection (fix round 1) that would otherwise look inexplicably absent.
     *   - the "no refund yet" narrowing and its warning. A return with no refund yet now emits its
     *     own RETURN row directly, so nothing is dropped and there is nothing to warn about — this is
     *     also precisely how this revision closes the false-positive gap described above: a
     *     return-only OMS row now has a genuine Shopify row to match against even before any refund
     *     for it exists.
     *
     * ASSOCIATION HISTORY (kept for context; no longer load-bearing since refunds and returns are
     * independent rows here): earlier revisions of this class tried to attach a returnId to each
     * refund record. Investigation found the GraphQL selection this class actually queries
     * (ShopifySourceCatalog's SHOPIFY_ORDER_RETURN_REFS fields: refunds.id, refunds.createdAt,
     * returns.nodes.id, returns.nodes.status, returns.nodes.createdAt — confirmed against both the
     * field list and the live-captured fixture,
     * src/test/resources/fixtures/shopify-order-return-refs-response.json) carries no field on either
     * side that names the other. Shopify's Admin GraphQL schema does publish a direct link —
     * Refund.return (a nullable back-reference to the Return object; see shopify.dev's Refund object
     * reference) — but it has never been live-probed against this component (an invalid field would
     * fail the WHOLE query at GraphQL validation time, not just degrade one column) and was
     * deliberately not selected speculatively. A fallback order-level pairing heuristic (pair a
     * refund with its order's one in-window return) was implemented and then rejected on review (fix
     * round 1, 2026-08-18): an order can legitimately carry a real return alongside an unrelated
     * refund — goodwill, shipping, a price adjustment — so the heuristic produced a confidently WRONG
     * returnId rather than an honest null. This revision makes the whole question moot: refunds and
     * returns are independent rows here and this class never associates one with the other. If a
     * future need arises to correlate a refund row with the return it actually belongs to (analytics,
     * not reconciliation), Refund.return remains the answer — still requiring a live probe first.
     *
     * ACCEPTED COST — report, do not silently work around: a refunded return now produces TWO rows
     * (its refund and the return itself) whenever both events fall in the same window. No live OMS
     * `reconciliationReturns` payload has ever been captured (OmsReturnsExtractTests.groovy's own
     * class doc, the other repo: "no swagger, fixture, or captured live response for this endpoint
     * exists"), so this is not live-verified either way. The documented OMS-side CONTRACT is
     * suggestive, though, and points one direction: OmsReturnsSourceSupport extracts one row per
     * physical OMS return record, and ReturnPresenceVerificationSupport's own doc describes exactly
     * one externalId per row, stamped with EITHER the refund id (usual case) OR, for a permanent
     * minority of returns imported while IN PROGRESS, the return id — "never backfilled to the
     * refund id once one issues" (that class's doc). Nothing in that contract describes a return
     * being represented by two OMS rows. If that holds, a refunded return most likely gets exactly
     * ONE OMS counterpart, and the OTHER of this class's two Shopify rows for it will read as a
     * permanent, structural missing-in-OMS row on every run — not a transient timing gap. This is a
     * known, disclosed limitation the plan explicitly accepted, not something to patch around in
     * this class; do not add filtering here to "fix" it without a deliberate, separate decision.
     *
     * KNOWN BREAKAGE (not this task's to fix): the other repo's ReturnPresenceVerificationSupport
     * reads the original refundIds/returnIds/refunds/returns fields (and would equally break against
     * the intermediate refundId/returnId shape) at multiple sites, and will fail against this shape
     * too until it is retired or rewritten — a separate, later task in the same plan. Do not "fix" it
     * from here without re-reading that class's own doc first (it has at least one behavior — a
     * per-order forward-match suppression — that is not a plain join and needs a deliberate call).
     */
    private static List<Map<String, Object>> toRecords(Map node, int refundsFirst, int returnsFirst,
                                                        long floorMillis, long windowEndMillis,
                                                        List<String> warnings) {
        String orderId = bareId(node.get("legacyResourceId") ?: node.get("id"))
        List<Map<String, String>> refundEvents = collectEvents(node.get("refunds"), orderId, "refunds", refundsFirst, warnings)
        List<Map<String, String>> returnEvents = collectEvents(node.get("returns"), orderId, "returns", returnsFirst, warnings)

        List<Map<String, String>> inWindowRefunds = inWindow(refundEvents, orderId, "refund", floorMillis, windowEndMillis, warnings)
        List<Map<String, String>> inWindowReturns = inWindow(returnEvents, orderId, "return", floorMillis, windowEndMillis, warnings)

        List<Map<String, Object>> records = []
        inWindowRefunds.each { Map<String, String> refund ->
            records.add([
                    eventId  : refund.id,
                    eventType: EVENT_TYPE_REFUND,
                    orderId  : orderId,
                    createdAt: refund.createdAt,
            ] as Map<String, Object>)
        }
        inWindowReturns.each { Map<String, String> ret ->
            records.add([
                    eventId  : ret.id,
                    eventType: EVENT_TYPE_RETURN,
                    orderId  : orderId,
                    createdAt: ret.createdAt,
            ] as Map<String, Object>)
        }
        return records
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
