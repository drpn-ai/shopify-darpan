package shopify.reconciliation.lookup

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Point-existence check for the returns pair's missing-in-Shopify rows.
 *
 * OMS stores a BARE numeric Shopify reference that may name a Refund OR a Return, and the diff row
 * carries only that id — no type. Rather than infer the type (digit length separates them in today's
 * data purely by coincidence of id ranges, and would silently stop working as ids grow), this looks
 * the id up under BOTH gid forms and treats it as present if either resolves. The verification pass
 * only needs existence, never type.
 */
class ShopifyRefundOrReturnLookupSupportTests {

    private static Map<String, Object> authConfig() {
        return [
            shopApiUrl : "https://example.myshopify.com/admin/api",
            apiVersion : "2026-04",
            accessToken: "shpat_secret_token",
        ]
    }

    /** Shopify returns one node entry per requested id, in order, null where nothing resolved. */
    private static Map<String, Object> nodesFor(List<String> requestedGids, Collection<String> resolving) {
        return [
            statusCode: 200,
            body      : JsonOutput.toJson([data: [nodes: requestedGids.collect { it in resolving ? [id: it] : null }]]),
        ]
    }

    @Test
    void findsAnIdThatResolvesOnlyAsARefund() {
        Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(
                authConfig(), ["954740703363"], [
                httpExecutor: { Map request ->
                    List gids = (List) ((Map) ((Map) request.body).variables).ids
                    nodesFor(gids, ["gid://shopify/Refund/954740703363"])
                },
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertEquals(["954740703363"], result.foundIds)
        assertEquals([], result.missingIds)
    }

    @Test
    void findsAnIdThatResolvesOnlyAsAReturn() {
        Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(
                authConfig(), ["26949222531"], [
                httpExecutor: { Map request ->
                    List gids = (List) ((Map) ((Map) request.body).variables).ids
                    nodesFor(gids, ["gid://shopify/Return/26949222531"])
                },
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertEquals(["26949222531"], result.foundIds)
        assertEquals([], result.missingIds)
    }

    @Test
    void sendsBothGidFormsForEachBareId() {
        List<String> sent = []
        ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(authConfig(), ["1234"], [
                httpExecutor: { Map request ->
                    List gids = (List) ((Map) ((Map) request.body).variables).ids
                    sent.addAll(gids as List<String>)
                    nodesFor(gids, [])
                },
        ])

        assertEquals(["gid://shopify/Refund/1234", "gid://shopify/Return/1234"], sent)
    }

    @Test
    void reportsMissingWhenNeitherFormResolves() {
        Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(
                authConfig(), ["9999"], [
                httpExecutor: { Map request ->
                    List gids = (List) ((Map) ((Map) request.body).variables).ids
                    nodesFor(gids, [])
                },
        ])

        assertTrue((Boolean) result.ok)
        assertEquals([], result.foundIds)
        assertEquals(["9999"], result.missingIds)
    }

    @Test
    void treatsUnqueryableIdsAsMissingWithoutSendingThem() {
        // Live data carries 7 such rows: five 48-character values and two that are not Shopify-shaped
        // at all. They can never resolve, and must stay REPORTED rather than be silently confirmed.
        String junk = "a" * 48
        List<String> sent = []
        Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(
                authConfig(), [junk, "26949222531"], [
                httpExecutor: { Map request ->
                    List gids = (List) ((Map) ((Map) request.body).variables).ids
                    sent.addAll(gids as List<String>)
                    nodesFor(gids, ["gid://shopify/Return/26949222531"])
                },
        ])

        assertTrue((Boolean) result.ok)
        assertEquals(["26949222531"], result.foundIds)
        assertEquals([junk], result.missingIds)
        assertFalse(sent.any { it.contains(junk) }, "an unqueryable id must never be sent to Shopify")
    }

    @Test
    void chunksSoThatBothFormsOfAnIdAlwaysShareOneCall() {
        // The nodes cap is 250 GIDs and each id costs two, so the id-per-call budget is 125. Splitting
        // an id's two forms across calls would let one call fail and strand a half-answer.
        List<Integer> gidCounts = []
        List<String> ids = (1..130).collect { it.toString() }
        Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(authConfig(), ids, [
                httpExecutor: { Map request ->
                    List gids = (List) ((Map) ((Map) request.body).variables).ids
                    gidCounts.add(gids.size())
                    nodesFor(gids, gids as List<String>)
                },
        ])

        assertTrue((Boolean) result.ok)
        assertEquals([250, 10], gidCounts)
        assertEquals(130, result.foundIds.size())
    }

    @Test
    void transportFailurePropagatesErrorsWithoutClassifyingIds() {
        Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(
                authConfig(), ["1234"], [
                httpExecutor: { Map request -> [statusCode: 500, body: "boom"] },
                maxAttempts : 1,
        ])

        assertFalse((Boolean) result.ok)
        assertTrue(((List) result.errors).size() > 0)
        // A failed lookup must never claim an id is missing — that would relabel real refunds.
        assertEquals([], result.foundIds)
        assertEquals([], result.missingIds)
    }

    @Test
    void emptyInputShortCircuitsWithoutHttpCalls() {
        int calls = 0
        Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(authConfig(), [], [
                httpExecutor: { Map request -> calls++; nodesFor([], []) },
        ])

        assertTrue((Boolean) result.ok)
        assertEquals(0, calls)
        assertEquals([], result.foundIds)
    }

    @Test
    void usesAnAlreadyGidShapedIdVerbatimAsItsOnlyForm() {
        List<String> sent = []
        Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(
                authConfig(), ["gid://shopify/Return/9999"], [
                httpExecutor: { Map request ->
                    List gids = (List) ((Map) ((Map) request.body).variables).ids
                    sent.addAll(gids as List<String>)
                    nodesFor(gids, ["gid://shopify/Return/9999"])
                },
        ])

        assertTrue((Boolean) result.ok)
        assertEquals(["gid://shopify/Return/9999"], sent)
        assertEquals(["gid://shopify/Return/9999"], result.foundIds)
    }

    @Test
    void queriesTheDatastoreDirectlyNotTheSearchIndex() {
        // The whole point: the extract's orders(query:) net misses old orders with new refunds.
        // A lookup served by the same search index would inherit the same blind spot.
        String query = null
        ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(authConfig(), ["1234"], [
                httpExecutor: { Map request ->
                    query = (String) ((Map) request.body).query
                    nodesFor((List) ((Map) ((Map) request.body).variables).ids, [])
                },
        ])

        assertTrue(query.contains("nodes(ids:"), "must resolve by id, not by search: ${query}")
    }

    @Test
    void oneFailedChunkStrandsOnlyItsOwnIds() {
        // DAR-BE-036: at 17,172 ids the pass is 138 sequential calls, so aborting the WHOLE pass on
        // the first throttled chunk turns one transient 429 into ~17,000 false differences. Ids a
        // successful chunk answered for must keep their answer.
        List<String> ids = (1..130).collect { it.toString() }
        int calls = 0
        Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(authConfig(), ids, [
                httpExecutor: { Map request ->
                    calls++
                    List gids = (List) ((Map) ((Map) request.body).variables).ids
                    return calls == 1 ? nodesFor(gids, gids as List<String>) : [statusCode: 500, body: "boom"]
                },
                maxAttempts : 1,
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertEquals(2, calls, "the failed chunk must not short-circuit the ids already answered")
        assertEquals((1..125).collect { it.toString() }, result.foundIds)
        // The stranded chunk is UNKNOWN, never "missing" — reporting it as missing is the exact
        // failure shape that let a blind spot read as evidence of absence.
        assertEquals([], result.missingIds)
        assertEquals((126..130).collect { it.toString() }, result.unresolvedIds)
        assertTrue(((List) result.errors).size() > 0)
    }

    @Test
    void everyChunkFailingClassifiesNothingAndStaysNotOk() {
        List<String> ids = (1..130).collect { it.toString() }
        Map<String, Object> result = ShopifyRefundOrReturnLookupSupport.lookupRefundOrReturnIds(authConfig(), ids, [
                httpExecutor: { Map request -> [statusCode: 500, body: "boom"] },
                maxAttempts : 1,
        ])

        assertFalse((Boolean) result.ok)
        assertEquals([], result.foundIds)
        assertEquals([], result.missingIds)
    }
}
