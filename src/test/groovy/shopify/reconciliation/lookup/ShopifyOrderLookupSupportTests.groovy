package shopify.reconciliation.lookup

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The verification-pass point lookup must check ids against Shopify's primary datastore via
 * nodes(ids:) — never a search-index-backed query — split found vs missing, and stay batched
 * under Shopify's 250-id nodes limit.
 */
class ShopifyOrderLookupSupportTests {

    private static Map<String, Object> authConfig() {
        return [
            shopApiUrl : "https://example.myshopify.com/admin/api",
            apiVersion : "2026-04",
            accessToken: "shpat_secret_token",
        ]
    }

    private static Map<String, Object> nodesResponse(List<String> foundGids) {
        return [
            statusCode: 200,
            body      : JsonOutput.toJson([data: [nodes: foundGids.collect { it == null ? null : [id: it] }]]),
        ]
    }

    @Test
    void splitsFoundAndMissingAndMapsNumericIdsToGids() {
        List<Map> requests = []
        Map<String, Object> result = ShopifyOrderLookupSupport.lookupOrderIds(
                authConfig(), ["6902423453827", "1111", "2222"], [
                httpExecutor: { Map request ->
                    requests.add(request)
                    // Shopify returns null node entries for ids that do not exist
                    return nodesResponse(["gid://shopify/Order/6902423453827", null, "gid://shopify/Order/2222"])
                },
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        assertEquals(["6902423453827", "2222"], result.foundIds)
        assertEquals(["1111"], result.missingIds)
        assertEquals(1, requests.size())
        Map body = (Map) requests[0].body
        assertEquals(
                ["gid://shopify/Order/6902423453827", "gid://shopify/Order/1111", "gid://shopify/Order/2222"],
                ((Map) body.variables).ids)
        // primary-datastore lookup, not a search query
        assertTrue(((String) body.query).contains("nodes(ids:"))
    }

    @Test
    void chunksRequestsAtNodesLimit() {
        List<Integer> chunkSizes = []
        List<String> ids = (1..260).collect { it.toString() }
        Map<String, Object> result = ShopifyOrderLookupSupport.lookupOrderIds(authConfig(), ids, [
                httpExecutor: { Map request ->
                    List requestedIds = (List) ((Map) ((Map) request.body).variables).ids
                    chunkSizes.add(requestedIds.size())
                    return nodesResponse(requestedIds as List<String>)
                },
        ])

        assertTrue((Boolean) result.ok)
        assertEquals([250, 10], chunkSizes)
        assertEquals(260, result.foundIds.size())
        assertEquals(0, result.missingIds.size())
    }

    @Test
    void transportFailurePropagatesErrorsWithoutClassifyingIds() {
        Map<String, Object> result = ShopifyOrderLookupSupport.lookupOrderIds(authConfig(), ["1234"], [
                httpExecutor: { Map request -> return [statusCode: 500, body: "boom"] },
                maxAttempts : 1,
        ])

        assertFalse((Boolean) result.ok)
        assertTrue(((List) result.errors).size() > 0)
        // a failed lookup must NOT claim any id is missing — that would relabel real orders
        assertEquals([], result.foundIds)
        assertEquals([], result.missingIds)
    }

    @Test
    void emptyInputShortCircuitsWithoutHttpCalls() {
        int calls = 0
        Map<String, Object> result = ShopifyOrderLookupSupport.lookupOrderIds(authConfig(), [], [
                httpExecutor: { Map request -> calls++; return nodesResponse([]) },
        ])

        assertTrue((Boolean) result.ok)
        assertEquals(0, calls)
        assertEquals([], result.foundIds)
        assertEquals([], result.missingIds)
    }

    @Test
    void passesThroughAlreadyGidShapedIds() {
        Map<String, Object> result = ShopifyOrderLookupSupport.lookupOrderIds(
                authConfig(), ["gid://shopify/Order/9999"], [
                httpExecutor: { Map request -> return nodesResponse(["gid://shopify/Order/9999"]) },
        ])

        assertTrue((Boolean) result.ok)
        assertEquals(["gid://shopify/Order/9999"], result.foundIds)
    }
}
