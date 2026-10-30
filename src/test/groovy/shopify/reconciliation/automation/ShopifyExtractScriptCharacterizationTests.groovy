package shopify.reconciliation.automation

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyBulkOperationClient

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue
import static shopify.reconciliation.ShopifyScriptCharacterizationSupport.assertMatchesGolden
import static shopify.reconciliation.ShopifyScriptCharacterizationSupport.folderContents
import static shopify.reconciliation.ShopifyScriptCharacterizationSupport.resetFolder
import static shopify.reconciliation.ShopifyScriptCharacterizationSupport.stripRunTimestamps

/**
 * Characterization of the three extract script services (extract#ShopifyOrders,
 * extract#ShopifyOrderLineUnits, extract#ShopifyOrderReturnRefs), run through the real service
 * definitions on a real Moqui ec. Only the Shopify boundary is replaced: the auth-config resolution,
 * the bulk-operation client and the return-refs cursor extraction are metaClass mocks that record
 * every argument they receive. Everything else (window parsing and normalization, source filters,
 * line-unit assembly, file naming, sidecar and raw-JSONL writes, the out-parameter filter) runs as
 * shipped, and each case is pinned as a golden snapshot.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyExtractScriptCharacterizationTests {
    private static final String SERVICES = "reconciliation.ShopifyOrderExtractionServices"
    private static final String TEST_TOKEN = "shopify-extract-characterization"
    private static final String OUTPUT_ROOT = "tmp/shopify-characterization/extract"

    private ExecutionContext ec

    List<Map> authCalls = []
    List<Map> bulkCalls = []
    List<Map> returnRefsCalls = []
    List<File> downloadDirectories = []
    Map authConfigFixture
    String authError
    Closure<Map> bulkResponder
    Closure<Map> returnRefsResponder

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), TEST_TOKEN)
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        def self = this
        ShopifyAuthConfigSupport.metaClass.static.requireUsableAuthConfig = { Object ecArg, Object configId, Map opts ->
            self.authCalls.add([configId: configId, opts: opts])
            if (self.authError) ecArg.message.addError(self.authError)
            return self.authConfigFixture
        }
        ShopifyBulkOperationClient.metaClass.static.runQuery = { Map cfg, String queryDocument, Map options ->
            // The download directory is the run's output folder, an absolute path: asserted where it matters, not snapshotted.
            self.bulkCalls.add([authConfig: cfg, queryDocument: queryDocument,
                                options   : options.findAll { it.key != "downloadDirectory" }])
            self.downloadDirectories.add((File) options.downloadDirectory)
            return asDownloaded(self.bulkResponder.call(), (File) options.downloadDirectory)
        }
        ShopifyReturnRefsSupport.metaClass.static.extractReturnRefs = { Map cfg, Object windowStart, Object windowEnd, Map options, Closure executor ->
            self.returnRefsCalls.add([authConfig: cfg, windowStart: windowStart, windowEnd: windowEnd, options: options,
                                      executorPassed: executor != null])
            return self.returnRefsResponder.call()
        }
    }

    @AfterAll
    void cleanup() {
        GroovySystem.metaClassRegistry.removeMetaClass(ShopifyAuthConfigSupport)
        GroovySystem.metaClassRegistry.removeMetaClass(ShopifyBulkOperationClient)
        GroovySystem.metaClassRegistry.removeMetaClass(ShopifyReturnRefsSupport)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        authCalls.clear()
        bulkCalls.clear()
        returnRefsCalls.clear()
        downloadDirectories.clear()
        authError = null
        authConfigFixture = [shopApiUrl : "https://krewe-test.myshopify.com", apiVersion: "2025-01",
                             accessToken: "shpat_characterization", timeZone: "UTC"]
        bulkResponder = { -> [ok: true, records: [], jsonlText: ""] }
        returnRefsResponder = { -> [records: [], warnings: [], errors: [], requestMetadata: [:]] }
        resetFolder(runtimeFile(OUTPUT_ROOT))
        resetFolder(runtimeFile("tmp/test-data-manager/${TEST_TOKEN}/reconciliation-runs"))
    }

    // ---- extract#ShopifyOrders --------------------------------------------------------------

    @Test
    void ordersHappyPathFiltersWritesRawJsonlSidecarAndMetadata() {
        bulkResponder = { -> ordersBulkResult() }
        Map result = call("extract#ShopifyOrders", "orders-happy", [
                windowStart           : "2026-08-01T00:00:00Z",
                windowEnd             : "2026-08-02T00:00:00Z",
                preserveWindowInstants: true,
                automationId          : "AUTO_1",
                fileSide              : "FILE_1",
                systemEnumId          : "SHOPIFY",
                sourceTypeEnumId      : "API",
                sourceFilters         : [[sequenceNum: 1, fieldExpression: "displayFinancialStatus",
                                          operator: "EXCLUDE_IN", filterValues: "VOIDED"]],
        ])
        assertEquals([], result.errors)
        assertEquals(2, result.recordCount)
        assertTrue(result.dataAvailable as boolean)
        assertEquals("shopify-orders-1785542400000-1785628800000.json", result.fileName)
        assertEquals(runtimeFile("${OUTPUT_ROOT}/orders-happy").canonicalFile, downloadDirectories[0].canonicalFile,
                "DAR-BE-162: the result downloads into the output folder, so the raw JSONL moves into place by rename")
        assertSnapshot("extract-orders-happy", result, "orders-happy")
    }

    @Test
    void ordersStreamAcrossFilterBatchesKeepingOrderCountsAndEveryDroppedOrder() {
        // DAR-BE-162: more orders than one filter batch, so the per-rule counts, the excluded sidecar and the order of
        // the kept records must come out as if the whole list had been filtered at once.
        int total = ShopifyExtractSupport.BatchedSourceFilter.BATCH_SIZE * 2 + 37
        List<Map> orders = (1..total).collect { int n ->
            [id: "gid://shopify/Order/${n}".toString(), name: "#${n}".toString(),
             displayFinancialStatus: n % 3 == 0 ? "VOIDED" : "PAID",
             transactions: n % 2 == 0 ? [[kind: "SALE", status: "SUCCESS", gateway: "shopify_payments"]] : []] as Map
        }
        bulkResponder = { -> [ok: true, records: orders, bulkOperation: [id: "gid://shopify/BulkOperation/7", status: "COMPLETED"]] }
        Map result = call("extract#ShopifyOrders", "orders-stream", window() + [
                sourceFilters: [[sequenceNum: 1, fieldExpression: "displayFinancialStatus", operator: "EXCLUDE_IN",
                                 filterValues: "VOIDED"]]])
        assertEquals([], result.errors)
        List<String> keptIds = orders.findAll { it.displayFinancialStatus != "VOIDED" }.collect { (it.id as String).tokenize("/").last() }
        assertEquals(keptIds, records(result)*.id)
        assertEquals(keptIds.size(), result.recordCount)
        Map metadata = (Map) result.requestMetadata
        assertEquals(total, metadata.extractedRecordCount)
        assertEquals(keptIds.size(), metadata.keptRecordCount)
        assertEquals([total.intdiv(3), 0], [metadata.filters.configuredExclusions[0].excludedCount,
                                           metadata.filters.configuredExclusions[0].fieldAbsentCount])
        assertEquals(keptIds.count { (it as int) % 2 == 0 }, metadata.paymentTransactionYCount)
        File folder = runtimeFile("${OUTPUT_ROOT}/orders-stream")
        File sidecar = folder.listFiles().find { it.name.endsWith(".excluded.json") }
        assertEquals(total.intdiv(3), ((List) ((Map) new JsonSlurper().parse(sidecar)).records).size())
        assertEquals([], folder.list().findAll { it.endsWith(".partial") } as List, "no work file is left behind")
    }

    @Test
    void ordersReadByKeyReadThoseOrdersByIdInChunksInsteadOfTheWindow() {
        // DAR-BE-156: an audit re-checks its older open findings by key, so Shopify must read exactly those orders.
        List<String> keys = (1..251).collect { (18959000000000L + it).toString() }
        bulkResponder = { -> [ok: true, records: [[id: "gid://shopify/Order/${18959000000000L + bulkCalls.size()}".toString(),
                                                   displayFulfillmentStatus: "FULFILLED"]], jsonlText: ""] }
        Map result = call("extract#ShopifyOrders", "orders-by-key", [
                windowStart           : "2026-10-27T00:00:00Z",
                windowEnd             : "2026-10-29T00:00:00Z",
                preserveWindowInstants: true,
                lookupKeys            : keys,
                lookupKeyField        : "id",
        ])
        assertEquals([], result.errors)
        assertEquals(2, bulkCalls.size(), "251 ids read as one chunk of ${ShopifyExtractSupport.LOOKUP_IDS_PER_BULK} and one of 1")
        bulkCalls.each { Map c -> assertFalse((c.queryDocument as String).contains("created_at"), "a read by key ignores the window") }
        assertTrue((bulkCalls[0].queryDocument as String).contains("(id:18959000000001 OR id:18959000000002 OR "))
        assertTrue((bulkCalls[1].queryDocument as String).contains('query: "(id:18959000000251)"'))
        assertEquals(2, result.recordCount, "both chunks' orders are written")
        assertEquals(["18959000000001", "18959000000002"], records(result)*.id)
    }

    @Test
    void ordersCalendarWindowDefaultLocationSanitizedNameAndNoRawJsonl() {
        authConfigFixture.remove("timeZone")
        bulkResponder = { -> [ok: true, records: [], bulkOperation: [id: "gid://shopify/BulkOperation/9", status: "COMPLETED"]] }
        Map result = call("extract#ShopifyOrders", null, [
                windowStart          : "2026-08-01",
                windowEnd            : "1785628800000",
                automationExecutionId: "EXEC_ORDERS_1",
                fileName             : "../../evil name",
        ])
        assertEquals([], result.errors)
        assertEquals(0, result.recordCount)
        assertFalse(result.dataAvailable as boolean)
        assertSnapshot("extract-orders-default-location", result, null)
    }

    @Test
    void ordersWindowErrorsReturnBeforeAuth() {
        Map result = call("extract#ShopifyOrders", "orders-window-error", [windowStart: "not-a-date", windowEnd: "  "])
        assertTrue(authCalls.isEmpty())
        assertTrue(bulkCalls.isEmpty())
        assertSnapshot("extract-orders-window-errors", result, "orders-window-error")
    }

    @Test
    void ordersAuthFailureCopiesMessageErrors() {
        authError = "Shopify auth config 'CFG_X' was not found."
        Map result = call("extract#ShopifyOrders", "orders-auth-error", window())
        assertTrue(bulkCalls.isEmpty())
        assertSnapshot("extract-orders-auth-error", result, "orders-auth-error")
    }

    @Test
    void ordersInvalidSourceFilterFailsBeforeBulk() {
        Map result = call("extract#ShopifyOrders", "orders-filter-error", window() + [
                sourceFilters: [[sequenceNum: 1, fieldExpression: "name", operator: "BOGUS", filterValues: "x"]]])
        assertTrue(bulkCalls.isEmpty())
        assertSnapshot("extract-orders-filter-error", result, "orders-filter-error")
    }

    @Test
    void ordersBulkFailureNormalizesErrors() {
        bulkResponder = { -> [ok: false, errors: ["Shopify said no", null, "   "]] }
        Map result = call("extract#ShopifyOrders", "orders-bulk-error", window() + [
                maxBulkPollAttempts: 0, bulkPollIntervalMillis: -5, bulkStartRetryAttempts: -1, bulkStartRetryDelayMillis: 7])
        assertSnapshot("extract-orders-bulk-error", result, "orders-bulk-error")
    }

    @Test
    void ordersBulkFailureWithoutErrorsUsesFallback() {
        bulkResponder = { -> [ok: false] }
        Map result = call("extract#ShopifyOrders", "orders-bulk-error-fallback", window())
        assertSnapshot("extract-orders-bulk-error-fallback", result, "orders-bulk-error-fallback")
    }

    // ---- extract#ShopifyOrderLineUnits ------------------------------------------------------

    @Test
    void lineUnitsHappyPathAssemblesFiltersAndWarns() {
        bulkResponder = { -> lineUnitsBulkResult() }
        Map result = call("extract#ShopifyOrderLineUnits", "line-units-happy", [
                windowStart           : "2026-08-01T00:00:00Z",
                windowEnd             : "2026-08-02T00:00:00Z",
                preserveWindowInstants: true,
                automationId          : "AUTO_2",
                fileSide              : "FILE_2",
                systemEnumId          : "SHOPIFY_ORDER_LINE_UNITS",
                sourceTypeEnumId      : "API",
                fileName              : "units",
                sourceFilters         : [[sequenceNum: 1, fieldExpression: "unitState",
                                          operator: "EXCLUDE_IN", filterValues: "REMOVED"]],
        ])
        assertEquals([], result.errors)
        assertSnapshot("extract-line-units-happy", result, "line-units-happy")
    }

    @Test
    void lineUnitsNoFiltersDefaultLocationNoRawJsonl() {
        authConfigFixture.remove("timeZone")
        bulkResponder = { -> [ok: true, records: lineUnitsBulkResult().records] }
        Map result = call("extract#ShopifyOrderLineUnits", null, [
                windowStart : "2026-08-01",
                windowEnd   : "2026-08-03",
                automationId: "AUTO_LINE_UNITS",
        ])
        assertSnapshot("extract-line-units-default-location", result, null)
    }

    @Test
    void lineUnitsAuthFailure() {
        authError = "Shopify auth config CFG_X is not enabled for SHOPIFY_ORDER_LINE_UNITS."
        Map result = call("extract#ShopifyOrderLineUnits", "line-units-auth-error", window())
        assertSnapshot("extract-line-units-auth-error", result, "line-units-auth-error")
    }

    @Test
    void lineUnitsWindowErrors() {
        Map result = call("extract#ShopifyOrderLineUnits", "line-units-window-error", [windowStart: "garbage", windowEnd: "2026-13-45"])
        assertSnapshot("extract-line-units-window-errors", result, "line-units-window-error")
    }

    @Test
    void lineUnitsInvalidSourceFilterFailsBeforeBulk() {
        Map result = call("extract#ShopifyOrderLineUnits", "line-units-filter-error", window() + [
                sourceFilters: [[sequenceNum: 1, fieldExpression: "unitState", operator: "NOPE", filterValues: "OPEN"]]])
        assertTrue(bulkCalls.isEmpty())
        assertSnapshot("extract-line-units-filter-error", result, "line-units-filter-error")
    }

    @Test
    void lineUnitsBulkFailure() {
        bulkResponder = { -> [ok: false, errors: ["Bulk operation FAILED", ""]] }
        Map result = call("extract#ShopifyOrderLineUnits", "line-units-bulk-error", window())
        assertSnapshot("extract-line-units-bulk-error", result, "line-units-bulk-error")
    }

    // ---- extract#ShopifyOrderReturnRefs -----------------------------------------------------

    @Test
    void returnRefsHappyPathWritesEnvelopeAndLeavesNoWorkFile() {
        returnRefsResponder = { -> returnRefsResult() }
        Map result = call("extract#ShopifyOrderReturnRefs", "return-refs-happy", [
                windowStart           : "2026-08-01T00:00:00Z",
                windowEnd             : "2026-08-02T00:00:00Z",
                preserveWindowInstants: true,
        ])
        assertEquals([], result.errors)
        assertEquals(2, result.recordCount)
        assertSnapshot("extract-return-refs-happy", result, "return-refs-happy")
    }

    @Test
    void returnRefsPassesOverridesAndSanitizesFileName() {
        authConfigFixture.timeZone = "America/Chicago"
        returnRefsResponder = { -> [records: [], warnings: null, errors: null, requestMetadata: null] }
        Map result = call("extract#ShopifyOrderReturnRefs", "return-refs-overrides", [
                windowStart       : "2026-08-01",
                windowEnd         : "2026-08-02",
                pageSize          : 25,
                connectionPageSize: 10,
                lookbackHours     : 6,
                fileName          : "../escape",
                sourceFilters     : [[sequenceNum: 1, fieldExpression: "orderReturnStatus", operator: "EXCLUDE_IN",
                                      filterValues: "IN_PROGRESS"]],
                connectTimeoutMillis: 1111,
                readTimeoutMillis : 2222,
                maxAttempts       : 3,
        ])
        assertFalse(result.dataAvailable as boolean)
        assertSnapshot("extract-return-refs-overrides", result, "return-refs-overrides")
    }

    @Test
    void returnRefsDefaultLocation() {
        returnRefsResponder = { -> returnRefsResult() }
        Map result = call("extract#ShopifyOrderReturnRefs", null, window() + [automationExecutionId: "EXEC_RR_1"])
        assertSnapshot("extract-return-refs-default-location", result, null)
    }

    @Test
    void returnRefsExtractionErrorsKeepMetadataAndWriteNothing() {
        returnRefsResponder = { -> [records: [[refundOrReturnId: "1"]], warnings: ["partial page"],
                                    errors: ["Shopify GraphQL throttled"], requestMetadata: [sourceType: "X"]] }
        Map result = call("extract#ShopifyOrderReturnRefs", "return-refs-error", window())
        assertSnapshot("extract-return-refs-error", result, "return-refs-error")
    }

    @Test
    void returnRefsAuthAndWindowFailures() {
        authError = "Shopify auth config CFG_X is inactive."
        Map authResult = call("extract#ShopifyOrderReturnRefs", "return-refs-auth-error", window())
        assertTrue(returnRefsCalls.isEmpty())
        assertSnapshot("extract-return-refs-auth-error", authResult, "return-refs-auth-error")

        reset()
        Map windowResult = call("extract#ShopifyOrderReturnRefs", "return-refs-window-error", [windowStart: "x", windowEnd: "y"])
        assertSnapshot("extract-return-refs-window-error", windowResult, "return-refs-window-error")
    }

    @Test
    void returnRefsMoveFailureStillDeletesTheWorkFile() {
        returnRefsResponder = { -> returnRefsResult() }
        File blocker = runtimeFile("${OUTPUT_ROOT}/return-refs-move-failure/blocked.json")
        blocker.mkdirs()
        new File(blocker, "occupant.txt").text = "keeps the directory non-empty"
        Map result = call("extract#ShopifyOrderReturnRefs", "return-refs-move-failure", window() + [fileName: "blocked.json"])
        assertEquals([:], result)
        assertTrue(ec.message.hasError())
        assertEquals(["blocked.json/occupant.txt": "keeps the directory non-empty"],
                folderContents(runtimeFile("${OUTPUT_ROOT}/return-refs-move-failure")))
        assertEquals(1, returnRefsCalls.size())
    }

    // ---- helpers ----------------------------------------------------------------------------

    private Map call(String verbNoun, String outputFolder, Map parameters) {
        Map params = [shopifyAuthConfigId: "CFG_CHAR", companyUserGroupId: "KREWE"] + parameters
        if (outputFolder) params.outputLocation = "runtime://${OUTPUT_ROOT}/${outputFolder}".toString()
        return ec.service.sync().name("${SERVICES}.${verbNoun}").parameters(params).call()
    }

    private void assertSnapshot(String golden, Map result, String outputFolder) {
        File folder
        if (outputFolder) {
            folder = runtimeFile("${OUTPUT_ROOT}/${outputFolder}")
        } else if (result?.fileLocation) {
            String location = result.fileLocation as String
            folder = runtimeFile(location.replace("runtime://", "").replaceFirst(/\/[^\/]+$/, ""))
        }
        assertMatchesGolden(golden, stripRunTimestamps([
                result         : result,
                ecErrors       : ec.message.getErrors(),
                authCalls      : authCalls,
                bulkCalls      : bulkCalls,
                returnRefsCalls: returnRefsCalls,
                files          : folderContents(folder),
        ]))
    }

    private File runtimeFile(String relative) {
        return new File(ec.factory.runtimePath, relative)
    }

    private List<Map> records(Map result) {
        File f = runtimeFile((result.fileLocation as String).replace("runtime://", ""))
        return (List<Map>) ((Map) new JsonSlurper().parse(f)).records
    }

    /** What the real client answers: the records as a downloaded JSONL file in the output folder, not in heap. */
    private static Map asDownloaded(Map result, File directory) {
        if (!(result?.records instanceof List)) return result
        Map downloaded = new LinkedHashMap(result)
        List records = (List) downloaded.remove("records")
        downloaded.remove("jsonlText")
        if (records) {
            File file = directory != null ? Files.createTempFile(directory.toPath(), "shopify-bulk-", ".jsonl.partial").toFile()
                    : Files.createTempFile("shopify-bulk-", ".jsonl.partial").toFile()
            file.withWriter("UTF-8") { Writer w -> records.each { w.write(JsonOutput.toJson(it)); w.write("\n") } }
            downloaded.jsonlFile = file
        }
        return downloaded
    }

    private static Map window() {
        return [windowStart: "2026-08-01T00:00:00Z", windowEnd: "2026-08-02T00:00:00Z", preserveWindowInstants: true]
    }

    private static Map ordersBulkResult() {
        return [
                ok               : true,
                records          : [
                        [id: "gid://shopify/Order/1001", name: "#1001", displayFinancialStatus: "PAID",
                         transactions: [[kind: "SALE", status: "SUCCESS", gateway: "shopify_payments"]]],
                        [id: "gid://shopify/Order/1002", legacyResourceId: "1002", name: "#1002",
                         displayFinancialStatus: "PENDING", transactions: []],
                        [id: "gid://shopify/Order/1003", name: "#1003", displayFinancialStatus: "VOIDED",
                         transactions: [[kind: "AUTHORIZATION", status: "SUCCESS", gateway: "manual"]]],
                        "not-a-map",
                ],
                jsonlText        : '{"id":"gid://shopify/Order/1001"}\n{"id":"gid://shopify/Order/1002"}\n{"id":"gid://shopify/Order/1003"}\n',
                bulkOperation    : [id: "gid://shopify/BulkOperation/1", status: "COMPLETED", objectCount: 3],
                pollCount        : 2,
                statusHistory    : [[status: "CREATED"], [status: "RUNNING"], [status: "COMPLETED"]],
                startRetryCount  : 1,
                startRetryHistory: [[attempt: 1, errors: ["A bulk query operation for this app and shop is already in progress"]]],
                jsonlLineCount   : 3,
        ]
    }

    private static Map lineUnitsBulkResult() {
        return [
                ok            : true,
                records       : [
                        [id: "gid://shopify/Order/2001", legacyResourceId: "2001", name: "#2001", returnStatus: "NO_RETURN",
                         sourceName: "web"],
                        [id: "gid://shopify/LineItem/31", __parentId: "gid://shopify/Order/2001", quantity: 3, sku: "SKU-A",
                         name: "Alpha", currentQuantity: 2, unfulfilledQuantity: 1],
                        [id: "gid://shopify/LineItem/32", __parentId: "gid://shopify/Order/2001", quantity: 0, sku: "SKU-Z",
                         name: "Zero"],
                        [id: "gid://shopify/LineItem/33", __parentId: "gid://shopify/Order/2001", quantity: 1, sku: "SKU-U",
                         name: "Unknown"],
                        [id: "gid://shopify/LineItem/34", __parentId: "gid://shopify/Order/9999", quantity: 1, sku: "SKU-O",
                         name: "Orphan"],
                        [id: "gid://shopify/LineItem/35", __parentId: "gid://shopify/Order/2001", quantity: 1, sku: "SKU-C",
                         name: "Clamped", currentQuantity: 5, unfulfilledQuantity: 9],
                ],
                jsonlText     : '{"id":"gid://shopify/Order/2001"}\n{"id":"gid://shopify/LineItem/31"}\n',
                bulkOperation : [id: "gid://shopify/BulkOperation/2", status: "COMPLETED"],
                pollCount     : 1,
                statusHistory : [[status: "COMPLETED"]],
                jsonlLineCount: 6,
        ]
    }

    private static Map returnRefsResult() {
        return [
                records        : [
                        [refundOrReturnId: "9001", refundOrReturnType: "REFUND", orderId: "1001", createdAt: "2026-08-01T05:00:00Z"],
                        [refundOrReturnId: "8001", refundOrReturnType: "RETURN", orderId: "1002", createdAt: "2026-08-01T06:00:00Z"],
                ],
                warnings       : ["Order 1003 had more refunds than the connection page size."],
                errors         : [],
                requestMetadata: [sourceType: "SHOPIFY_GRAPHQL_ORDER_RETURN_REFS", pageCount: 1,
                                  filters   : [closedUnrefundedReturnsSuppressed: 0]],
        ]
    }
}
