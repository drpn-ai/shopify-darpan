import darpan.common.ValueSupport
import darpan.facade.common.DataManagerSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.SourceFilterSupport
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
Map<String, Object> builtBulkQuery
try {
    builtBulkQuery = ShopifyExtractSupport.buildCreatedAtBulkQuery(ShopifySourceCatalog.SHOPIFY_ORDERS,
            "DarpanShopifyOrdersByDateWindow", (String) window.startText, (String) window.endText)
} catch (Exception e) {
    errors = [normalize(e.message) ?: "Shopify bulk query could not be built."]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}
String searchQuery = (String) builtBulkQuery.searchQuery
Map<String, Object> bulkOperationResult = ShopifyExtractSupport.runBulkQuery(authConfig,
        (String) builtBulkQuery.queryDocument, context)

List<Map<String, Object>> records = []
if (bulkOperationResult.ok == false) {
    outputErrors.addAll(ShopifyExtractSupport.bulkErrors(bulkOperationResult))
} else {
    records = ((List) (bulkOperationResult.records ?: []))
            .findAll { Object record -> record instanceof Map }
            .collect { Object record -> normalizeShopifyOrderRecord.call((Map<String, Object>) record) } as List<Map<String, Object>>
}
Map filteredOrders = SourceFilterSupport.applyToRecords(records, parsedSourceFilters)
int extractedRecordCount = records.size()
records = (List<Map<String, Object>>) filteredOrders.records
if (outputErrors) {
    ExcludedRecordsSidecar.discard((Map) filteredOrders.excludedCollector)
    errors = outputErrors
    warnings = outputWarnings
    dataAvailable = false
    recordCount = records.size()
    return
}

String outputBaseLocation = ShopifyExtractSupport.resolveOutputBaseLocation(ec, outputLocation,
        automationExecutionId, automationId, configIdValue)
String outputFileName = ShopifyExtractSupport.safeFileName(
        fileName ?: "shopify-orders-${sourceWindowStart.time}-${sourceWindowEnd.time}.json",
        "shopify-orders.json"
)
Map<String, String> rawJsonl = ShopifyExtractSupport.writeRawJsonl(ec, bulkOperationResult, outputBaseLocation,
        outputFileName, "shopify-orders.jsonl")
fileName = outputFileName
fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
fileTypeEnumId = "DftJson"
ShopifyExtractSupport.writeExcludedSidecar(ec, (Map) filteredOrders.excludedCollector, outputBaseLocation,
        outputFileName, outputWarnings)
// DAR-BE-064 canary: the presence OPP rule (<=) cannot see a missing signal, so report it here.
Map paymentSummary = ShopifyOrderPaymentSignal.summarize(records)
String paymentCanary = ShopifyOrderPaymentSignal.canaryWarning(paymentSummary)
if (paymentCanary) outputWarnings.add(paymentCanary)
recordCount = records.size()
dataAvailable = records.size() > 0
Map<String, Object> metadata = ShopifyExtractSupport.bulkRequestMetadata("SHOPIFY_GRAPHQL_ORDERS", configIdValue,
        context, window, searchQuery, bulkOperationResult, rawJsonl)
metadata.putAll([
        extractedRecordCount    : extractedRecordCount,
        keptRecordCount         : records.size(),
        paymentTransactionYCount: paymentSummary.paymentTransactionYCount,
        paymentTransactionNCount: paymentSummary.paymentTransactionNCount,
        // Absent (not an empty block) when no rules are configured, matching the line-units extract.
        filters                 : filteredOrders.configuredExclusions != null ?
                [configuredExclusions: filteredOrders.configuredExclusions] : null,
])
requestMetadata = metadata.findAll { it.value != null } as Map<String, Object>
ShopifyExtractSupport.writeExtractFile(ec, fileLocation as String, requestMetadata, records)
warnings = outputWarnings
errors = []
