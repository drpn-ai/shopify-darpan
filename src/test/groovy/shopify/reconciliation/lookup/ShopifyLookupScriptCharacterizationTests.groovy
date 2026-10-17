package shopify.reconciliation.lookup

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import shopify.facade.settings.ShopifyAuthConfigSupport

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static shopify.reconciliation.ShopifyScriptCharacterizationSupport.assertMatchesGolden

/**
 * Characterization of the four lookup script services (lookup#ShopifyOrderIds,
 * lookup#ShopifyRefundOrReturnIds, lookup#ShopifyExchangeSweep, lookup#ShopifyOrderExchangeState),
 * run through the real service definitions on a real Moqui ec. Auth-config resolution and each
 * lookup's support-class entry point are metaClass mocks that record their arguments; the support
 * classes themselves are covered by their own unit tests. Each case is pinned as a golden snapshot
 * of the service result (after the out-parameter filter), ec.message errors and recorded calls.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyLookupScriptCharacterizationTests {
    private static final String SERVICES = "reconciliation.ShopifyOrderExtractionServices"

    private ExecutionContext ec

    List<Map> authCalls = []
    List<Map> supportCalls = []
    Map authConfigFixture
    String authError
    Map supportResult

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(),
                "shopify-lookup-characterization")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        def self = this
        ShopifyAuthConfigSupport.metaClass.static.requireUsableAuthConfig = { Object ecArg, Object configId, Map opts ->
            self.authCalls.add([configId: configId, opts: opts])
            if (self.authError) ecArg.message.addError(self.authError)
            return self.authConfigFixture
        }
        ShopifyOrderLookupSupport.metaClass.static.lookupOrderIds = { Map cfg, List ids, Map options ->
            self.supportCalls.add([method: "lookupOrderIds", authConfig: cfg, ids: ids, options: options])
            return self.supportResult
        }
        ShopifyRefundOrReturnLookupSupport.metaClass.static.lookupRefundOrReturnIds = { Map cfg, List ids, Map options ->
            self.supportCalls.add([method: "lookupRefundOrReturnIds", authConfig: cfg, ids: ids, options: options])
            return self.supportResult
        }
        ShopifyExchangeSweepSupport.metaClass.static.sweepExchanges = { Map cfg, long start, long end, Map options ->
            self.supportCalls.add([method: "sweepExchanges", authConfig: cfg, start: start, end: end, options: options])
            return self.supportResult
        }
        ShopifyExchangeStateLookupSupport.metaClass.static.lookupExchangeState = { Map cfg, Collection ids, Map options ->
            self.supportCalls.add([method: "lookupExchangeState", authConfig: cfg, ids: ids, options: options])
            return self.supportResult
        }
    }

    @AfterAll
    void cleanup() {
        [ShopifyAuthConfigSupport, ShopifyOrderLookupSupport, ShopifyRefundOrReturnLookupSupport,
         ShopifyExchangeSweepSupport, ShopifyExchangeStateLookupSupport].each {
            GroovySystem.metaClassRegistry.removeMetaClass(it)
        }
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        authCalls.clear()
        supportCalls.clear()
        authError = null
        authConfigFixture = [shopApiUrl : "https://krewe-test.myshopify.com", apiVersion: "2025-01",
                             accessToken: "shpat_characterization", timeZone: "UTC"]
        supportResult = null
    }

    @Test
    void orderIdsPassThroughAndDefaults() {
        supportResult = [ok: true, foundIds: ["1"], missingIds: ["2"], errors: [], extraKey: "dropped by the service"]
        Map result = call("lookup#ShopifyOrderIds", [orderIds: ["1", "gid://shopify/Order/2"]])
        assertEquals(true, result.ok)
        snapshot("lookup-order-ids-ok", result)
    }

    @Test
    void orderIdsAuthFailure() {
        authError = "Shopify auth config 'CFG_CHAR' was not found."
        Map result = call("lookup#ShopifyOrderIds", [orderIds: ["1"], connectTimeoutMillis: 5, readTimeoutMillis: 6, maxAttempts: 1])
        assertTrue(supportCalls.isEmpty())
        snapshot("lookup-order-ids-auth-error", result)
    }

    @Test
    void refundOrReturnIdsPassThroughIncludingUnresolved() {
        supportResult = [ok: true, foundIds: ["10"], missingIds: [], unresolvedIds: ["11"], errors: ["one chunk failed"]]
        Map result = call("lookup#ShopifyRefundOrReturnIds", [refundOrReturnIds: ["10", "11"], connectTimeoutMillis: 7])
        snapshot("lookup-refund-or-return-ids-ok", result)
    }

    @Test
    void refundOrReturnIdsNullUnresolvedBecomesEmpty() {
        supportResult = [ok: false, foundIds: [], missingIds: [], errors: ["dead lookup"]]
        Map result = call("lookup#ShopifyRefundOrReturnIds", [refundOrReturnIds: ["10"]])
        assertEquals([], result.unresolvedIds)
        snapshot("lookup-refund-or-return-ids-null-unresolved", result)
    }

    @Test
    void refundOrReturnIdsAuthFailure() {
        authError = "Shopify auth config CFG_CHAR is inactive."
        Map result = call("lookup#ShopifyRefundOrReturnIds", [refundOrReturnIds: ["10"]])
        snapshot("lookup-refund-or-return-ids-auth-error", result)
    }

    @Test
    void exchangeSweepPassThrough() {
        supportResult = [ok: true, exchanges: [[orderId: "1", returnId: "2"]], truncated: true, errors: []]
        Map result = call("lookup#ShopifyExchangeSweep", [windowStartMillis: 1785542400000L, windowEndMillis: 1785628800000L,
                                                          maxAttempts: 4])
        snapshot("lookup-exchange-sweep-ok", result)
    }

    @Test
    void exchangeSweepAuthFailure() {
        authError = "Shopify auth config CFG_CHAR is not enabled for SHOPIFY."
        Map result = call("lookup#ShopifyExchangeSweep", [windowStartMillis: 1L, windowEndMillis: 2L])
        snapshot("lookup-exchange-sweep-auth-error", result)
    }

    @Test
    void exchangeStatePassThrough() {
        supportResult = [ok: true, statesByOrderId: ["1": [returns: []]], errors: [], lineItemsShape: "NODES"]
        Map result = call("lookup#ShopifyOrderExchangeState", [orderIds: ["1"], readTimeoutMillis: 9])
        snapshot("lookup-exchange-state-ok", result)
    }

    @Test
    void exchangeStateAuthFailure() {
        authError = "Shopify auth config 'CFG_CHAR' was not found."
        Map result = call("lookup#ShopifyOrderExchangeState", [orderIds: ["1"]])
        snapshot("lookup-exchange-state-auth-error", result)
    }

    private Map call(String verbNoun, Map parameters) {
        Map params = [shopifyAuthConfigId: "CFG_CHAR", companyUserGroupId: "KREWE"] + parameters
        return ec.service.sync().name("${SERVICES}.${verbNoun}").parameters(params).call()
    }

    private void snapshot(String golden, Map result) {
        assertMatchesGolden(golden, [
                result      : result,
                ecErrors    : ec.message.getErrors(),
                authCalls   : authCalls,
                supportCalls: supportCalls,
        ])
    }
}
