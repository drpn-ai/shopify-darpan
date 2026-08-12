package shopify.facade.settings

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 review finding — {@code shopify.facade.settings.ShopifyAuthConfigSupport.findAuthConfig}
 * was the one Seam C site the Task 8 audit missed: it resolves its entity through the {@code
 * ENTITY_NAME} constant rather than a literal entity-name string, so a grep for the literal
 * {@code "darpan.shopify.ShopifyAuthConfig"} string never matched it. A bare {@code
 * ec.entity.find(ENTITY_NAME)} there is silently filtered by {@code DARPAN_ACTIVE_COMPANY_SCOPE}
 * (SecuritySeedData.xml {@code DARPAN_SCOPE_SHOPIFY_AUTH}) on any authz-enabled read, so a
 * foreign-owned row came back {@code null} — and {@code saveAuthConfig} treats a null
 * {@code existingConfig} as "this id is free, create it", reassigning the row to the caller's own
 * tenant and overwriting its {@code accessToken}. This is the same defect fixed on the darpan side
 * for {@code save#NsAuthConfig} / {@code save#NsRestletConfig} in commit {@code a70e809}.
 *
 * <p><strong>Why this must be a static source assertion, not a runtime/service test.</strong> Moqui's
 * {@code authzDisabled} flag is a single non-scoped boolean set for an ENTIRE nested execution
 * ({@code ArtifactExecutionFacadeImpl}, {@code ServiceCallSyncImpl}) — it is not scoped per entity
 * find. Every existing runtime test in this component (see {@code ShopifyAuthConfigFacadeSmokeTests}
 * and {@code ShopifySharedConfigAccessTests}) invokes the facade services via
 * {@code ec.service.sync()...disableAuthz().call()}. That call disables authz for the WHOLE nested
 * execution before {@code findAuthConfig}'s own {@code ec.entity.find} ever runs, so
 * {@code DARPAN_ACTIVE_COMPANY_SCOPE} never applies inside a test — a foreign-owned row is ALWAYS
 * visible there, whether {@code findAuthConfig} calls a bare {@code ec.entity.find} or the tenant
 * scoped finder. Reverting the fix back to a bare {@code ec.entity.find} would therefore fail no
 * runtime test in this repo; a static source assertion is the only way to pin the invariant. This is
 * the exact technique {@code darpan.facade.common.SharedConfigEntityContractTests
 * .saveNsAuthAndNsRestletConfigResolveTheirRecordsThroughFindGlobalUnscopedNotABareEntityFind} uses
 * for the equivalent darpan-side fix.</p>
 *
 * <p>Scoped to one method body at a time (via {@link #methodSource}), not the whole file: the test
 * file itself constructs finders directly for fixture setup, so a whole-file match would collide
 * with that. {@code requireUsableAuthConfig} was fixed the same way in the 2026-08-11 review pass
 * (finding B3) — it was the last bare {@code ec.entity.find(ENTITY_NAME)} in this file, reachable
 * from the interactive branch (e.g. {@code ShopifyGraphqlFacadeSupport.executeGraphql}, which calls
 * it with empty {@code opts}, so no {@code disableAuthz} ever reaches the finder) — so it is pinned
 * below by the same technique, not left as a documented exception.</p>
 */
class ShopifyAuthConfigEntityContractTests {

    private static String supportSource() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        return backendRoot.resolve(
                "runtime/component/shopify-darpan/src/main/groovy/shopify/facade/settings/ShopifyAuthConfigSupport.groovy"
        ).toFile().text
    }

    /**
     * Extracts just one method's body by brace-balancing from its signature — methods in this file
     * have no nested string/comment braces, so a simple depth counter is exact. Using the method body
     * (not the whole file, not a fixed line range that drifts under edits) is what makes the
     * assertions below immune to unrelated changes elsewhere in the file while still failing hard if
     * the target method itself regresses.
     */
    private static String methodSource(String source, String signature) {
        int start = source.indexOf(signature)
        assertTrue(start >= 0, "Could not find '${signature}' in ShopifyAuthConfigSupport.groovy")

        int braceStart = source.indexOf("{", start)
        assertTrue(braceStart >= 0, "Could not find the opening brace of ${signature}")

        int depth = 0
        int i = braceStart
        for (; i < source.length(); i++) {
            String c = source.substring(i, i + 1)
            if (c == "{") depth++
            else if (c == "}") {
                depth--
                if (depth == 0) { i++; break }
            }
        }
        assertTrue(depth == 0, "Brace balance for ${signature} never closed — extraction is unsafe")
        return source.substring(start, i)
    }

    @Test
    void findAuthConfigResolvesThroughFindGlobalUnscopedNotABareEntityFind() {
        String methodBody = methodSource(supportSource(), "static def findAuthConfig(")

        assertFalse(methodBody.contains("ec.entity.find("),
                "findAuthConfig must not resolve ShopifyAuthConfig via a bare ec.entity.find — " +
                "DARPAN_ACTIVE_COMPANY_SCOPE (DARPAN_SCOPE_SHOPIFY_AUTH) silently filters it to the " +
                "active tenant on any authz-enabled read, hiding a foreign-owned row from every " +
                "downstream access gate (requireTenantAuthConfigAccess / " +
                "SharedConfigAccessSupport.canActiveTenantUseConfig) before it can even run. That " +
                "silent null is exactly what let saveAuthConfig treat a foreign row as free-to-create " +
                "and reassign its ownership to the caller (a live cross-tenant credential hijack).")

        assertTrue(methodBody.contains("TenantScopedFinder.findGlobalUnscoped(ec, ENTITY_NAME"),
                "findAuthConfig must resolve the record through " +
                "darpan.facade.common.TenantScopedFinder.findGlobalUnscoped so a foreign-owned row " +
                "(shared or not) is visible to the caller's own access gate")
    }

    /**
     * DAR-BE-005 review finding B3 — {@code requireUsableAuthConfig} was the last bare
     * {@code ec.entity.find(ENTITY_NAME)} in this file. Its interactive branch (opts without
     * {@code companyUserGroupId}, e.g. {@code ShopifyGraphqlFacadeSupport.executeGraphql} calling it
     * with {@code [:]}) has no automation-supplied {@code disableAuthz} either, so the same seam C
     * hazard {@code findAuthConfig} had applies: DARPAN_ACTIVE_COMPANY_SCOPE silently filters the
     * read to the active tenant, hiding a peer's shared config before
     * {@code requireTenantAuthConfigAccess} / {@code canActiveTenantUseConfig} ever runs. Same
     * "must be a static source assertion, not a runtime test" rationale as
     * {@link #findAuthConfigResolvesThroughFindGlobalUnscopedNotABareEntityFind} above — every
     * runtime test in this component boots via {@code ReconciliationSmokeTestSupport.initMoqui},
     * which disables authz for the whole ExecutionContext for the test class's entire lifetime
     * (never re-enabled), so no runtime test here can ever observe DARPAN_ACTIVE_COMPANY_SCOPE
     * filtering regardless of which read shape {@code requireUsableAuthConfig} uses.
     */
    @Test
    void requireUsableAuthConfigResolvesThroughFindGlobalUnscopedNotABareEntityFind() {
        String methodBody = methodSource(supportSource(), "static def requireUsableAuthConfig(")

        assertFalse(methodBody.contains("ec.entity.find("),
                "requireUsableAuthConfig must not resolve ShopifyAuthConfig via a bare ec.entity.find " +
                "— DARPAN_ACTIVE_COMPANY_SCOPE (DARPAN_SCOPE_SHOPIFY_AUTH) silently filters it to the " +
                "active tenant on any authz-enabled read (the interactive/GraphQL branch has no " +
                "disableAuthz opt), hiding a peer's shared config from both downstream gates " +
                "(canTenantUseConfig / requireTenantAuthConfigAccess) before either can even run.")

        assertTrue(methodBody.contains("TenantScopedFinder.findGlobalUnscoped(ec, ENTITY_NAME"),
                "requireUsableAuthConfig must resolve the record through " +
                "darpan.facade.common.TenantScopedFinder.findGlobalUnscoped so a foreign-owned row " +
                "(shared or not) is visible to the caller's own access gate")
    }
}
