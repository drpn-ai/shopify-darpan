import darpan.common.ValueSupport
import darpan.facade.common.DataManagerSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.SourceFilterSupport
import shopify.graphql.ShopifyBulkOperationClient
import shopify.graphql.ShopifySourceCatalog
// Moqui extract scripts carry no package declaration, so an unimported class reference compiles
// fine (Groovy treats it as a dynamic property) and fails only at RUNTIME. Import every support class.
import shopify.reconciliation.automation.ShopifyExtractSupport
import shopify.reconciliation.automation.ShopifyOrderPaymentSignal

import java.sql.Timestamp

List<String> outputWarnings = []

Closure<String> normalize = { Object value -> ValueSupport.normalize(value) }
Closure<Map<String, Object>> normalizeShopifyOrderRecord = { Map<String, Object> record ->
    Map<String, Object> normalizedRecord = new LinkedHashMap<>(record ?: [:])
    String gid = normalize(normalizedRecord.id)
    String legacyId = normalize(normalizedRecord.legacyResourceId)
    if (!legacyId && gid) {
        def matcher = gid =~ /(\d+)$/
        if (matcher.find()) legacyId = matcher.group(1)
    }
    if (gid) normalizedRecord.shopifyGid = gid
    if (legacyId) {
        normalizedRecord.legacyResourceId = legacyId
        normalizedRecord.id = legacyId
    }
    // DAR-BE-064: derived before the source filters run, so a filter and the presence OPP rule can both read it.
    normalizedRecord.hasPaymentTransaction = ShopifyOrderPaymentSignal.hasPaymentTransaction(normalizedRecord.transactions)
    // DAR-BE-153: the transaction ids HotWax keeps as OPP manualRefNum; compared against HotWax paymentRefNums.
    normalizedRecord.paymentTransactionIds = ShopifyOrderPaymentSignal.paymentTransactionIds(normalizedRecord.transactions)
    // DAR-BE-153: every successful transaction id, so each HotWax OPP manualRefNum can be found among them.
    normalizedRecord.transactionIds = ShopifyOrderPaymentSignal.transactionIds(normalizedRecord.transactions)
    // DAR-BE-125 (R20): Y only when Shopify fulfilled the whole order; compared against HotWax isCompleted.
    normalizedRecord.isFulfilled = normalize(normalizedRecord.displayFulfillmentStatus) == "FULFILLED" ? "Y" : "N"
    return normalizedRecord
}
Map<String, Object> resolved = ShopifyExtractSupport.resolveConfigAndWindow(ec, shopifyAuthConfigId, companyUserGroupId,
        windowStart, windowEnd, "SHOPIFY")
List<String> outputErrors = (List<String>) resolved.errors
String configIdValue = (String) resolved.configIdValue
def authConfig = resolved.authConfig

if (outputErrors) {
    errors = outputErrors
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}

// DAR-BE-063: tenant source filters run CLIENT-side per order (Shopify search syntax knows nothing of
// them). Parsed BEFORE the bulk operation is submitted, so a malformed rule fails in seconds.
List<Map<String, Object>> parsedSourceFilters
try {
    parsedSourceFilters = SourceFilterSupport.parseRules(sourceFilters)
} catch (Exception e) {
    errors = [normalize(e.message) ?: "Configured exclusion rules are invalid."]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}

Map<String, Object> window = ShopifyExtractSupport.resolveSourceWindow(ec, authConfig,
        (Timestamp) resolved.windowStartValue, (Timestamp) resolved.windowEndValue, preserveWindowInstants)
Timestamp sourceWindowStart = (Timestamp) window.start
Timestamp sourceWindowEnd = (Timestamp) window.end
// DAR-BE-156: a read by key (an audit re-checking its older open findings) searches those order ids, not the window.
List<String> idKeys = (lookupKeys instanceof List) ? ((List) lookupKeys).collect { normalize(it) }.findAll { it }.unique() as List<String> : []
if (idKeys && !(normalize(lookupKeyField) in [null, "id", "legacyResourceId"])) {
    errors = ["Shopify orders can be read by order id only, not by ${lookupKeyField}.".toString()]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}
List<Map<String, Object>> builtBulkQueries = []
try {
    builtBulkQueries = idKeys ?
            idKeys.collate(ShopifyExtractSupport.LOOKUP_IDS_PER_BULK).collect { List<String> chunk ->
                ShopifyExtractSupport.buildIdsBulkQuery(ShopifySourceCatalog.SHOPIFY_ORDERS, "DarpanShopifyOrdersByIds", chunk)
            } :
            [ShopifyExtractSupport.buildCreatedAtBulkQuery(ShopifySourceCatalog.SHOPIFY_ORDERS,
                    "DarpanShopifyOrdersByDateWindow", (String) window.startText, (String) window.endText)]
} catch (Exception e) {
    errors = [normalize(e.message) ?: "Shopify bulk query could not be built."]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}
String searchQuery = idKeys ? "id IN (${idKeys.size()} ids)".toString() : (String) builtBulkQueries[0].searchQuery
String outputBaseLocation = ShopifyExtractSupport.resolveOutputBaseLocation(ec, outputLocation,
        automationExecutionId, automationId, configIdValue)
File outputDirectory = ShopifyExtractSupport.outputDirectory(ec, outputBaseLocation)

// DAR-BE-162: each bulk result downloads to a file and is read one order at a time: normalized, filtered a batch at a
// time, and written straight to the extract. Holding the result as text and then as maps OOMed a 2 GB JVM on a month.
Map<String, Integer> paymentSummary = [paymentTransactionYCount: 0, paymentTransactionNCount: 0]
ShopifyExtractSupport.ExtractFileWriter extractWriter = new ShopifyExtractSupport.ExtractFileWriter(outputDirectory)
ShopifyExtractSupport.BatchedSourceFilter sourceFilter = new ShopifyExtractSupport.BatchedSourceFilter(parsedSourceFilters,
        { Map kept ->
            ShopifyOrderPaymentSignal.tally(paymentSummary, kept)
            extractWriter.add(kept)
        })
Map<String, Object> bulkOperationResult = null
Map filteredOrders = null
int extractedRecordCount = 0
try {
    for (Map<String, Object> builtBulkQuery : builtBulkQueries) {
        // A read by key runs one bulk query per chunk; only the last chunk's raw JSONL is kept beside the extract.
        ShopifyExtractSupport.discardBulkResult(bulkOperationResult)
        bulkOperationResult = ShopifyExtractSupport.runBulkQuery(authConfig, (String) builtBulkQuery.queryDocument,
                context, outputDirectory)
        if (bulkOperationResult.ok == false) {
            outputErrors.addAll(ShopifyExtractSupport.bulkErrors(bulkOperationResult))
            break
        }
        ShopifyBulkOperationClient.eachJsonlRecord((File) bulkOperationResult.jsonlFile) { Map<String, Object> record ->
            extractedRecordCount++
            sourceFilter.add(normalizeShopifyOrderRecord.call(record))
        }
    }
    filteredOrders = sourceFilter.finish()
    if (outputErrors) {
        errors = outputErrors
        warnings = outputWarnings
        dataAvailable = false
        recordCount = extractWriter.recordCount
        return
    }

    String outputFileName = ShopifyExtractSupport.safeFileName(
            fileName ?: "shopify-orders-${sourceWindowStart.time}-${sourceWindowEnd.time}.json",
            "shopify-orders.json"
    )
    Map<String, String> rawJsonl = ShopifyExtractSupport.moveRawJsonl(ec, bulkOperationResult, outputBaseLocation,
            outputFileName, "shopify-orders.jsonl")
    fileName = outputFileName
    fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
    fileTypeEnumId = "DftJson"
    ShopifyExtractSupport.writeExcludedSidecar(ec, (Map) filteredOrders.excludedCollector, outputBaseLocation,
            outputFileName, outputWarnings)
    // DAR-BE-064 canary: the presence OPP rule (<=) cannot see a missing signal, so report it here.
    String paymentCanary = ShopifyOrderPaymentSignal.canaryWarning(paymentSummary)
    if (paymentCanary) outputWarnings.add(paymentCanary)
    recordCount = extractWriter.recordCount
    dataAvailable = extractWriter.recordCount > 0
    Map<String, Object> metadata = ShopifyExtractSupport.bulkRequestMetadata("SHOPIFY_GRAPHQL_ORDERS", configIdValue,
            context, window, searchQuery, bulkOperationResult, rawJsonl)
    metadata.putAll([
            extractedRecordCount    : extractedRecordCount,
            keptRecordCount         : extractWriter.recordCount,
            paymentTransactionYCount: paymentSummary.paymentTransactionYCount,
            paymentTransactionNCount: paymentSummary.paymentTransactionNCount,
            // Absent (not an empty block) when no rules are configured, matching the line-units extract.
            filters                 : filteredOrders.configuredExclusions != null ?
                    [configuredExclusions: filteredOrders.configuredExclusions] : null,
    ])
    requestMetadata = metadata.findAll { it.value != null } as Map<String, Object>
    extractWriter.finish(ec, fileLocation as String, requestMetadata)
    warnings = outputWarnings
    errors = []
} finally {
    extractWriter.abort()
    ShopifyExtractSupport.discardBulkResult(bulkOperationResult)
    // The excluded-records spill holds customer records: gone whether the sidecar was written, not wanted, or failed.
    ExcludedRecordsSidecar.discard(sourceFilter.excludedCollector)
}
