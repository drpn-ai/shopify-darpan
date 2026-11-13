package shopify.facade.settings

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import shopify.graphql.ShopifyGraphqlTransport

import java.nio.file.Path
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-275 — expiring offline tokens. The case that bites: a refresh token works once, so two jobs
 * refreshing at the same moment must not both spend it (the loser would store a dead token and cut
 * the store off). Also: a pasted token is never touched, and a Shopify outage never throws tokens away.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyTokenRefreshSmokeTests {
    private static final String TENANT = "KREWE"
    private static final String APP_ID = "SHOPIFY_APP_REFRESH_TEST"
    private static final String SHOP = "refresh-test.myshopify.com"

    private ExecutionContext ec
    private final AtomicInteger refreshCalls = new AtomicInteger()

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "shopify-token-refresh-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("darpan.shopify.ShopifyApp").setAll([
                shopifyAppId: APP_ID, distribution: "CUSTOM", shopDomain: SHOP, clientId: "cid",
                clientSecret: "secret", scopes: "read_orders", isActive: "Y"]).createOrUpdate()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @AfterEach
    void resetHook() {
        ShopifyOAuthClient.resetPostHook()
        refreshCalls.set(0)
    }

    @Test
    void aPastedTokenIsReturnedAsStoredAndNeverRefreshed() {
        def config = saveConfig("PASTED_CFG", [shopifyAppId: null, accessToken: "shpat_pasted", accessTokenExpiresDate: null])
        countingHook(200, [:])
        assertEquals("shpat_pasted", ShopifyTokenSupport.accessTokenFor(ec, config))
        assertEquals(0, refreshCalls.get())
        assertTrue(ShopifyAuthConfigSupport.transportConfig(config).accessTokenSupplier == null)
    }

    @Test
    void aTokenWithTimeLeftIsNotRefreshed() {
        saveConfig("FRESH_CFG", [accessToken: "shpat_fresh", refreshToken: "shprt_1", accessTokenExpiresDate: inMinutes(30)])
        countingHook(200, [:])
        assertEquals("shpat_fresh", ShopifyTokenSupport.currentAccessToken(ec, "FRESH_CFG"))
        assertEquals(0, refreshCalls.get())
    }

    @Test
    void aDueTokenIsRefreshedAndBothNewTokensAreStored() {
        def config = saveConfig("DUE_CFG", [accessToken: "shpat_old", refreshToken: "shprt_old", accessTokenExpiresDate: inMinutes(2)])
        countingHook(200, newTokens("shpat_new", "shprt_new"))

        // The transport asks the supplier on each request, so a long bulk read keeps a live token.
        Map transport = ShopifyAuthConfigSupport.transportConfig(config)
        assertEquals("shpat_new", ShopifyGraphqlTransport.resolveAccessToken(transport))

        def stored = read("DUE_CFG")
        assertEquals("shpat_new", stored.accessToken)
        assertEquals("shprt_new", stored.refreshToken)
        assertTrue(((Timestamp) stored.accessTokenExpiresDate).after(inMinutes(50)))
        assertEquals(1, refreshCalls.get())
    }

    @Test
    void twoJobsRefreshingAtOnceSpendTheRefreshTokenOnce() {
        saveConfig("RACE_CFG", [accessToken: "shpat_old", refreshToken: "shprt_once", accessTokenExpiresDate: inMinutes(1)])
        ShopifyOAuthClient.setPostHook { String url, Map form ->
            refreshCalls.incrementAndGet()
            Thread.sleep(400) // hold the row lock long enough for the other job to arrive
            [status: 200, body: newTokens("shpat_after_race", "shprt_after_race")]
        }
        CountDownLatch start = new CountDownLatch(1)
        List<String> results = Collections.synchronizedList([])
        List<Throwable> errors = Collections.synchronizedList([])
        List<Thread> threads = (1..2).collect {
            Thread.start {
                ExecutionContext threadEc = Moqui.getExecutionContext()
                try {
                    threadEc.artifactExecution.disableAuthz()
                    start.await()
                    results.add(ShopifyTokenSupport.currentAccessToken(threadEc, "RACE_CFG"))
                } catch (Throwable t) {
                    errors.add(t)
                } finally {
                    threadEc.destroy()
                }
            }
        }
        start.countDown()
        threads*.join(15000)

        assertTrue(errors.isEmpty(), "${errors}")
        assertEquals(1, refreshCalls.get(), "the single-use refresh token was spent twice")
        assertEquals(["shpat_after_race", "shpat_after_race"], results)
        assertEquals("shprt_after_race", read("RACE_CFG").refreshToken)
    }

    @Test
    void aRefusedRefreshTokenClearsTheTokensSoTheStoreReinstalls() {
        saveConfig("REVOKED_CFG", [accessToken: "shpat_old", refreshToken: "shprt_revoked", accessTokenExpiresDate: inMinutes(1)])
        countingHook(401, [error: "invalid_request"])
        assertNull(ShopifyTokenSupport.currentAccessToken(ec, "REVOKED_CFG"))
        def stored = read("REVOKED_CFG")
        assertNull(stored.accessToken)
        assertNull(stored.refreshToken)
    }

    @Test
    void aShopifyOutageKeepsTheTokensAndUsesTheCurrentOneWhileItLasts() {
        saveConfig("OUTAGE_CFG", [accessToken: "shpat_still_ok", refreshToken: "shprt_keep", accessTokenExpiresDate: inMinutes(3)])
        countingHook(503, [:])
        assertEquals("shpat_still_ok", ShopifyTokenSupport.currentAccessToken(ec, "OUTAGE_CFG"))
        assertEquals("shprt_keep", read("OUTAGE_CFG").refreshToken)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private def saveConfig(String id, Map<String, Object> fields) {
        Map<String, Object> base = [shopifyAuthConfigId: id, companyUserGroupId: TENANT, shopApiUrl: "https://${SHOP}".toString(),
                                    apiVersion: "2026-04", isActive: "Y", shopifyAppId: APP_ID]
        ec.entity.makeValue("darpan.shopify.ShopifyAuthConfig").setAll(base + fields).createOrUpdate()
        return read(id)
    }

    private def read(String id) {
        return ec.entity.find("darpan.shopify.ShopifyAuthConfig").condition("shopifyAuthConfigId", id).useCache(false).one()
    }

    private Timestamp inMinutes(int minutes) {
        return new Timestamp(ec.user.nowTimestamp.time + minutes * 60_000L)
    }

    private void countingHook(int status, Map body) {
        ShopifyOAuthClient.setPostHook { String url, Map form ->
            refreshCalls.incrementAndGet()
            [status: status, body: body]
        }
    }

    private static Map newTokens(String access, String refresh) {
        return [access_token: access, scope: "read_orders", expires_in: 3600, refresh_token: refresh, refresh_token_expires_in: 7776000]
    }
}
