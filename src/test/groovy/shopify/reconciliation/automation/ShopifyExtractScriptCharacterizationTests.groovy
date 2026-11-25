package shopify.reconciliation.automation

import darpan.reconciliation.source.KeepRules
import darpan.reconciliation.source.SourceFilterSupport
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

    private static final List<Map> VOIDED_FILTER = [[sequenceNum: 1, fieldExpression: "displayFinancialStatus",
                                                     operator: "EXCLUDE_IN", filterValues: "VOIDED"]]

    private static final List<String> PARITY_KEPT_IDS = ["10", "13", "20", "25", "30", "33", "38"]
    private static final List<Map> PARITY_COUNTS = [
            [sequenceNum: 1, excludedCount: 24, fieldAbsentCount: 0], [sequenceNum: 2, excludedCount: 0, fieldAbsentCount: 3],
            [sequenceNum: 3, excludedCount: 4, fieldAbsentCount: 0], [sequenceNum: 4, excludedCount: 1, fieldAbsentCount: 0],
            [sequenceNum: 5, excludedCount: 1, fieldAbsentCount: 0]]

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
    void ordersHappyPathWritesEveryOrderRawJsonlAndMetadata() {
        bulkResponder = { -> ordersBulkResult() }
        Map result = call("extract#ShopifyOrders", "orders-happy", [
                windowStart           : "2026-08-01T00:00:00Z",
                windowEnd             : "2026-08-02T00:00:00Z",
                preserveWindowInstants: true,
                automationId          : "AUTO_1",
                fileSide              : "FILE_1",
                systemEnumId          : "SHOPIFY",
                sourceTypeEnumId      : "API",
        ])
        assertEquals([], result.errors)
        assertEquals(3, result.recordCount)
        assertTrue(result.dataAvailable as boolean)
        assertEquals("shopify-orders-1785542400000-1785628800000.json", result.fileName)
        assertEquals(runtimeFile("${OUTPUT_ROOT}/orders-happy").canonicalFile, downloadDirectories[0].canonicalFile,
                "DAR-BE-162: the result downloads into the output folder, so the raw JSONL moves into place by rename")
        assertSnapshot("extract-orders-happy", result, "orders-happy")
    }

    @Test
    void ordersWithLineItemsSelectTheLinesAndPutEachUnderItsOrder() {
        // DAR-BE-368: asked withLineItems, the bulk query selects the lines and each order carries its own; off, it selects none.
        bulkResponder = { -> [ok: true, bulkOperation: [id: "gid://shopify/BulkOperation/8", status: "COMPLETED"], records: [
                [id: "gid://shopify/Order/2001", name: "#2001", displayFulfillmentStatus: "UNFULFILLED", transactions: []],
                [id: "gid://shopify/LineItem/71", sku: "SKU-A", name: "Anorak", quantity: 2, currentQuantity: 2,
                 unfulfilledQuantity: 2, __parentId: "gid://shopify/Order/2001"],
                [id: "gid://shopify/LineItem/72", sku: "SKU-B", name: "Cap", quantity: 1, currentQuantity: 0,
                 unfulfilledQuantity: 0, __parentId: "gid://shopify/Order/2001"],
                [id: "gid://shopify/Order/2002", name: "#2002", displayFulfillmentStatus: "FULFILLED", transactions: []],
        ]] }
        Map result = call("extract#ShopifyOrders", "orders-with-lines", window() + [withLineItems: true])
        assertEquals([], result.errors)
        assertTrue(bulkCalls[-1].queryDocument.toString().contains("lineItems"), "the lines are selected")
        List<Map> written = records(result)
        assertEquals(["2001", "2002"], written*.id)
        assertEquals([[id: "71", sku: "SKU-A", name: "Anorak", quantity: 2, currentQuantity: 2, unfulfilledQuantity: 2],
                      [id: "72", sku: "SKU-B", name: "Cap", quantity: 1, currentQuantity: 0, unfulfilledQuantity: 0]],
                written[0].lineItems)
        assertEquals([], written[1].lineItems)
        assertEquals(2, result.recordCount)
        assertEquals(0, ((Map) result.requestMetadata).orphanLines)

        bulkResponder = { -> ordersBulkResult() }
        call("extract#ShopifyOrders", "orders-without-lines", window())
        assertFalse(bulkCalls[-1].queryDocument.toString().contains("lineItems"), "off, the read is unchanged")
    }

    @Test
    void ordersStreamEveryOrderInOrderWhateverTheFilters() {
        // DAR-BE-162 / DAR-BE-318: more orders than the old filter batch held (500), each written in the order read, none dropped.
        int total = 500 * 2 + 37
        List<Map> orders = (1..total).collect { int n ->
            [id: "gid://shopify/Order/${n}".toString(), name: "#${n}".toString(),
             displayFinancialStatus: n % 3 == 0 ? "VOIDED" : "PAID",
             transactions: n % 2 == 0 ? [[kind: "SALE", status: "SUCCESS", gateway: "shopify_payments"]] : []] as Map
        }
        bulkResponder = { -> [ok: true, records: orders, bulkOperation: [id: "gid://shopify/BulkOperation/7", status: "COMPLETED"]] }
        Map result = call("extract#ShopifyOrders", "orders-stream", window() + [sourceFilters: VOIDED_FILTER])
        assertEquals([], result.errors)
        assertEquals((1..total).collect { it.toString() }, records(result)*.id)
        assertEquals(total, result.recordCount)
        Map metadata = (Map) result.requestMetadata
        assertEquals(total, metadata.extractedRecordCount)
        assertEquals(total, metadata.keptRecordCount)
        assertEquals((1..total).count { it % 2 == 0 }, metadata.paymentTransactionYCount)
        assertFalse(metadata.containsKey("filters"))
        File folder = runtimeFile("${OUTPUT_ROOT}/orders-stream")
        assertEquals([], folder.list().findAll { it.endsWith(".excluded.json") || it.endsWith(".partial") } as List,
                "no excluded-records side file and no work file is left behind")
    }

    @Test
    void everyOrderIsWrittenWhateverTheFilters() {
        // DAR-BE-318 (Task 21): the orders extract no longer declares sourceFilters, so Spark Keep owns the filtering (the gate
        // reads the declared parameters). Valid, invalid and absent filters all write every order and report no filter block.
        assertFalse(ec.service.getServiceDefinition("${SERVICES}.extract#ShopifyOrders").inParameterNameList.contains("sourceFilters"),
                "extract#ShopifyOrders must not declare sourceFilters, or the Keep gate keeps the read-time filter")
        List<List<Map>> variants = [null, [], VOIDED_FILTER,
                                    [[sequenceNum: 1, fieldExpression: "name", operator: "BOGUS", filterValues: "x"]]]
        variants.eachWithIndex { List<Map> filters, int i ->
            bulkResponder = { -> ordersBulkResult() }
            Map result = this.call("extract#ShopifyOrders", "orders-all-${i}", window() + (filters == null ? [:] : [sourceFilters: filters]))
            assertEquals([], result.errors, "variant ${i}")
            assertEquals(3, result.recordCount, "variant ${i}")
            assertEquals(["1001", "1002", "1003"], records(result)*.id, "variant ${i}")
            assertFalse(((Map) result.requestMetadata).containsKey("filters"))
            assertEquals(3, ((Map) result.requestMetadata).keptRecordCount)
            assertEquals([], runtimeFile("${OUTPUT_ROOT}/orders-all-${i}").list().findAll { it.endsWith(".excluded.json") } as List)
        }
        assertEquals(4, bulkCalls.size(), "a malformed rule no longer fails pre-flight: it never reaches the extract")
    }

    @Test
    void theUnfilteredExtractIsExactlyWhatTheReadTimeFilterWasHanded() {
        // DAR-BE-318 parity: Shopify-shaped orders, the same rules. Old = SourceFilterSupport.applyToRecords over the extract's
        // normalized records (what the deleted BatchedSourceFilter ran, 500 at a time). New = the unfiltered extract, which Spark Keep (darpan core,
        // KeepColumnParityTests) sets aside from by these same rules. This repo's tests cannot boot Spark (no Spark on its classpath).
        List<Map> orders = (1..40).collect { int n ->
            Map order = [id: "gid://shopify/Order/${n}".toString(), name: "#${n}".toString(),
                         displayFinancialStatus: ["PAID", "VOIDED", "voided ", "PENDING", "REFUNDED"][n % 5],
                         sourceName: ["web", "pos", "shopify_draft_order", "WEB", " pos"][n % 5],
                         subtotalLineItemsQuantity: n % 4 + 1,
                         test: n % 7 == 0,
                         tags: n % 6 == 0 ? ["wholesale", "vip"] : [],
                         transactions: n % 2 == 0 ? [[kind: "SALE", status: "SUCCESS", gateway: "shopify_payments"]] : []] as Map
            if (n % 8 == 0) order.remove("sourceName")           // field absent
            if (n % 9 == 0) order.sourceName = null               // JSON null
            if (n % 10 == 0) order.remove("displayFinancialStatus")
            return order
        }
        List<Map> filters = [
                [sequenceNum: 1, fieldExpression: "displayFinancialStatus", operator: "EXCLUDE_IN", filterValues: "VOIDED,refunded"],
                [sequenceNum: 2, fieldExpression: "sourceName", operator: "INCLUDE_IN", filterValues: "web,pos"],
                [sequenceNum: 3, fieldExpression: "itemQuantity", operator: "EXCLUDE_IN", filterValues: "4"],
                [sequenceNum: 4, fieldExpression: "test", operator: "EXCLUDE_IN", filterValues: "true"],
                [sequenceNum: 5, fieldExpression: "name", operator: "INCLUDE_PREFIX", filterValues: "#1,#2,#3,#4"],
        ]
        bulkResponder = { -> [ok: true, records: orders, bulkOperation: [id: "gid://shopify/BulkOperation/8", status: "COMPLETED"]] }
        Map result = call("extract#ShopifyOrders", "orders-parity", window())
        assertEquals([], result.errors)
        assertEquals(40, result.recordCount)
        File extract = runtimeFile((result.fileLocation as String).replace("runtime://", ""))
        List<Map> written = (List<Map>) ((Map) new JsonSlurper().parse(extract)).records

        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules(filters)
        Map old = SourceFilterSupport.applyToRecords(written, parsed)
        List<String> oldKept = ((List<Map>) old.records).collect { it.id as String }.sort()
        assertTrue(oldKept.size() in 1..<40, "the fixture keeps some and sets some aside: ${oldKept.size()}")

        // What the Keep step is handed from these same rules: valid, one rule per filter, owned by the filter's sequence number.
        List<Map> keepRules = SourceFilterSupport.toKeepRules(parsed)
        KeepRules.validate(keepRules)
        assertEquals(["A:1", "A:2", "A:3", "A:4", "A:5"], keepRules*.id)
        assertEquals(parsed*.fieldExpression, keepRules*.label)
        // The extract is the old filter's input, unfiltered and in read order: every record the old run saw is there.
        assertEquals((1..40).collect { it.toString() }, written*.id)
        assertEquals(oldKept, old.records*.id.sort())
        // The old run's filter outcome on this data, pinned: if the rules or the data change this changes with them.
        assertEquals(PARITY_KEPT_IDS, oldKept)
        assertEquals(PARITY_COUNTS, ((List<Map>) old.configuredExclusions).collect {
            [sequenceNum: it.sequenceNum, excludedCount: it.excludedCount, fieldAbsentCount: it.fieldAbsentCount]
        })
    }

    @Test
    void ordersWindowFieldNameReachesTheBulkQueryAndAnUnknownOneIsRefused() {
        // DAR-BE-318: windowFieldName updatedAt windows the read on updated_at; anything else never reaches Shopify.
        Map result = call("extract#ShopifyOrders", "orders-updated-window", [
                windowStart: "2026-08-01T00:00:00Z", windowEnd: "2026-08-02T00:00:00Z",
                preserveWindowInstants: true, windowFieldName: "updatedAt"])
        assertEquals([], result.errors)
        String doc = bulkCalls[0].queryDocument as String
        assertTrue(doc.contains("updated_at:>='2026-08-01T00:00:00Z' updated_at:<'2026-08-02T00:00:00Z'"), doc)
        assertFalse(doc.contains("created_at"))
        bulkCalls.clear()
        Map refused = call("extract#ShopifyOrders", "orders-bad-window", [
                windowStart: "2026-08-01T00:00:00Z", windowEnd: "2026-08-02T00:00:00Z",
                preserveWindowInstants: true, windowFieldName: "processedAt"])
        assertFalse(refused.dataAvailable as boolean)
        assertTrue((refused.errors as List).toString().contains("createdAt or updatedAt"), refused.errors.toString())
        assertEquals(0, bulkCalls.size())
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
    void lineUnitsHappyPathAssemblesEveryUnitAndWarns() {
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
    void everyUnitIsWrittenWhateverTheFilters() {
        // DAR-BE-318 (Task 22): the line-units extract no longer declares sourceFilters, so Spark Keep owns the filtering (the gate
        // reads the declared parameters). Valid, invalid and absent filters all write every unit and report no filter block.
        assertFalse(ec.service.getServiceDefinition("${SERVICES}.extract#ShopifyOrderLineUnits").inParameterNameList.contains("sourceFilters"),
                "extract#ShopifyOrderLineUnits must not declare sourceFilters, or the Keep gate keeps the read-time filter")
        List<List<Map>> variants = [null, [], [[sequenceNum: 1, fieldExpression: "unitState", operator: "EXCLUDE_IN", filterValues: "REMOVED"]],
                                    [[sequenceNum: 1, fieldExpression: "unitState", operator: "NOPE", filterValues: "OPEN"]]]
        variants.eachWithIndex { List<Map> filters, int i ->
            bulkResponder = { -> lineUnitsBulkResult() }
            Map result = this.call("extract#ShopifyOrderLineUnits", "line-units-all-${i}", window() + (filters == null ? [:] : [sourceFilters: filters]))
            assertEquals([], result.errors, "variant ${i}")
            assertEquals(5, result.recordCount, "variant ${i}")
            assertEquals(["OPEN", "FULFILLED", "REMOVED", "UNKNOWN", "OPEN"], records(result)*.unitState, "variant ${i}")
            assertFalse(((Map) result.requestMetadata).containsKey("filters"))
            // The grain's own drops stay at flattening and stay counted.
            assertEquals([1, 1], [((Map) result.requestMetadata).orphanLineItemCount, ((Map) result.requestMetadata).droppedZeroQuantity])
            assertEquals([], runtimeFile("${OUTPUT_ROOT}/line-units-all-${i}").list().findAll { it.endsWith(".excluded.json") } as List)
        }
        assertEquals(4, bulkCalls.size(), "a malformed rule no longer fails pre-flight: it never reaches the extract")
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
    void theReturnRefsWindowFieldIsCreatedAtOnly() {
        // DAR-BE-318: the events are windowed on their own createdAt; any other field never reaches Shopify.
        returnRefsResponder = { -> returnRefsResult() }
        Map ok = call("extract#ShopifyOrderReturnRefs", "return-refs-window-field", window() + [windowFieldName: "createdAt"])
        assertEquals([], ok.errors)
        assertEquals(1, returnRefsCalls.size())
        returnRefsCalls.clear()
        ["updatedAt", "processedAt"].each { String field ->
            Map refused = this.call("extract#ShopifyOrderReturnRefs", "return-refs-window-field-bad", window() + [windowFieldName: field])
            assertFalse(refused.dataAvailable as boolean)
            assertTrue((refused.errors as List).toString().contains(field), refused.errors.toString())
            assertTrue((refused.errors as List).toString().contains("createdAt"), refused.errors.toString())
        }
        // the completed-returns read is per order, with no event date to window on
        Map completed = call("extract#ShopifyOrderReturnRefs", "return-refs-window-field-completed",
                window() + [windowFieldName: "createdAt", completedReturns: "refunded"])
        assertFalse(completed.dataAvailable as boolean)
        assertTrue((completed.errors as List).toString().contains("completedReturns"), completed.errors.toString())
        assertEquals(0, returnRefsCalls.size(), "a refused window field never reaches Shopify")
    }

    @Test
    void anAbsentWindowFieldIsToday() {
        // no windowFieldName: the extract is what it was (its snapshot is returnRefsHappyPathWritesEnvelopeAndLeavesNoWorkFile)
        // and the support class is handed exactly what it is handed with the event's field named
        returnRefsResponder = { -> returnRefsResult() }
        Map absent = call("extract#ShopifyOrderReturnRefs", "return-refs-field-absent", window())
        Map named = call("extract#ShopifyOrderReturnRefs", "return-refs-field-named", window() + [windowFieldName: "createdAt"])
        assertEquals([], absent.errors)
        assertEquals([], named.errors)
        assertEquals(2, returnRefsCalls.size())
        assertEquals(returnRefsCalls[0], returnRefsCalls[1])
        assertEquals(absent.recordCount, named.recordCount)
        assertEquals(returnRefsCalls[0].options.lookbackHours, 3, "the lookback stays 3 h unless a call says otherwise")
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
                connectTimeoutMillis: 1111,
                readTimeoutMillis : 2222,
                maxAttempts       : 3,
        ])
        assertFalse(result.dataAvailable as boolean)
        assertSnapshot("extract-return-refs-overrides", result, "return-refs-overrides")
    }

    @Test
    void returnRefsDeclaresNoSourceFiltersAndPassesNoneToTheExtract() {
        // DAR-BE-318 (Task 23): the return-refs extract no longer declares sourceFilters, so the Keep gate places the side in Spark.
        // Asserted on Moqui's live merged definition, which is what the gate reads.
        assertFalse(ec.service.getServiceDefinition("${SERVICES}.extract#ShopifyOrderReturnRefs").inParameterNameList.contains("sourceFilters"),
                "extract#ShopifyOrderReturnRefs must not declare sourceFilters, or the Keep gate keeps the read-time filter")
        returnRefsResponder = { -> returnRefsResult() }
        Map result = call("extract#ShopifyOrderReturnRefs", "return-refs-no-filters", window() + [
                sourceFilters: [[sequenceNum: 1, fieldExpression: "orderReturnStatus", operator: "EXCLUDE_IN", filterValues: "IN_PROGRESS"]]])
        assertEquals([], result.errors)
        assertEquals(1, returnRefsCalls.size())
        assertFalse(returnRefsCalls[0].options.containsKey("sourceFilters"), "the extract must hand the support class no filters: ${returnRefsCalls[0].options}")
        assertEquals([], runtimeFile("${OUTPUT_ROOT}/return-refs-no-filters").list().findAll { it.endsWith(".excluded.json") || it.endsWith(".keep.json") } as List)
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
