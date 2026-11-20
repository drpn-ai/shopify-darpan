package shopify.reconciliation.automation

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyGraphqlTransport

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1, Task 4 fix round 1 (I1). The REAL extract#ShopifyOrderReturnRefs script and the REAL
 * ShopifyReturnRefsSupport, with only the Shopify transport answered by a stub: what a dataset read (preserveWindowInstants
 * true, lookbackHours 0, the connector's datasetReadParametersJson) selects is exactly the events created in
 * [windowStart, windowEnd) by their own createdAt, with no lookback and no rounding to days, and what the read selects without
 * lookbackHours 0 is the widened window the live run read keeps.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyReturnRefsInstantWindowTests {
    private static final String SERVICE = "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrderReturnRefs"

    private ExecutionContext ec
    private List<Map> sent = []
    private int seq = 0

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "shopify-return-refs-instant-window")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        def self = this
        ShopifyAuthConfigSupport.metaClass.static.requireUsableAuthConfig = { Object ecArg, Object configId, Map opts ->
            return [shopApiUrl: "https://krewe-test.myshopify.com", apiVersion: "2025-01", accessToken: "shpat_x",
                    timeZone: "America/Los_Angeles"]
        }
        ShopifyGraphqlTransport.metaClass.static.execute = { Map cfg, String doc, Map vars, Map opts ->
            self.sent << [doc: doc, vars: vars]
            if (doc.contains("events(")) {
                return [ok: true, data: [events: [nodes: ORDERS.collect { Map o ->
                    [id: "gid://shopify/Event/${o.refund}".toString(), action: "refund_created", subjectType: "Order",
                     subjectId: "gid://shopify/Order/${o.order}".toString(), createdAt: o.createdAt]
                }, pageInfo: [hasNextPage: false, endCursor: null]]]]
            }
            return [ok: true, data: [nodes: ORDERS.collect { Map o ->
                [id: "gid://shopify/Order/${o.order}".toString(), legacyResourceId: o.order, name: "#${o.order}".toString(),
                 createdAt: "2026-01-01T00:00:00Z",
                 refunds: [[id: "gid://shopify/Refund/${o.refund}".toString(), createdAt: o.createdAt]], returns: [nodes: []]]
            }]]
        }
    }

    @AfterAll
    void cleanup() {
        GroovySystem.metaClassRegistry.removeMetaClass(ShopifyAuthConfigSupport)
        GroovySystem.metaClassRegistry.removeMetaClass(ShopifyGraphqlTransport)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        sent.clear()
    }

    /** Refunds around the window [2026-11-10T08:00Z, 2026-11-11T08:00Z): 701 is 90 minutes early, 702/703 a boundary pair at the start, 704/705 at the end. */
    private static final List<Map> ORDERS = [
            [order: "1", refund: "701", createdAt: "2026-11-10T06:30:00Z"],
            [order: "2", refund: "702", createdAt: "2026-11-10T08:00:00Z"],
            [order: "3", refund: "703", createdAt: "2026-11-10T07:59:59Z"],
            [order: "4", refund: "704", createdAt: "2026-11-11T07:59:59Z"],
            [order: "5", refund: "705", createdAt: "2026-11-11T08:00:00Z"],
    ]

    @Test
    void aDatasetReadSelectsExactlyTheEventsCreatedInTheInstantWindow() {
        Map out = read([lookbackHours: 0])
        assertEquals([], out.errors)
        assertEquals(["702", "704"], ids(out), "no 90-minute lookback, and the boundary pair is split at the instant")
        String events = sent.find { it.doc.contains("events(") }.vars.query
        assertTrue(events.contains("created_at:>='2026-11-10T08:00:00Z'"), events)
        assertTrue(events.contains("created_at:<'2026-11-11T08:00:00Z'"), "no rounding to whole days in the store's zone: ${events}")
    }

    @Test
    void theSameReadWithItsDefaultLookbackKeepsTheEarlyEvent() {
        Map out = read([:])
        assertEquals([], out.errors)
        assertEquals(["701", "702", "703", "704"], ids(out), "the live run read widens the start by 3 h")
        assertTrue((sent.find { it.doc.contains("events(") }.vars.query as String).contains("created_at:>='2026-11-10T05:00:00Z'"))
    }

    private Map read(Map extra) {
        Map params = [shopifyAuthConfigId: "CFG_IW", companyUserGroupId: "KREWE", windowStart: "2026-11-10T08:00:00Z",
                      windowEnd: "2026-11-11T08:00:00Z", preserveWindowInstants: true, windowFieldName: "createdAt",
                      outputLocation: "runtime://tmp/shopify-instant-window/${++seq}".toString()] + extra
        Map result = ec.service.sync().name(SERVICE).parameters(params).call()
        return [errors: (result?.errors ?: []) + ec.message.errors.collect { it.toString() }, fileLocation: result?.fileLocation]
    }

    private List<String> ids(Map out) {
        File f = ec.resource.getLocationReference(out.fileLocation as String).getFile()
        return ((Map) new JsonSlurper().parse(f, "UTF-8")).records*.refundOrReturnId.sort()
    }
}
