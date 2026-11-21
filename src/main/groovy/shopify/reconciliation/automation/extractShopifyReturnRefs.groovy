import darpan.common.ValueSupport
import darpan.facade.common.DataManagerSupport
import groovy.json.JsonOutput
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyCallTally
import shopify.reconciliation.automation.ShopifyCompletedReturnsSupport
import shopify.reconciliation.automation.ShopifyExtractSupport
import shopify.reconciliation.automation.ShopifyReturnRefsSupport

import java.sql.Timestamp

List<String> outputWarnings = []
// DAR-BE-318: the events are windowed on their own createdAt, which is all this read does; any other field is refused
// before Shopify is called. A completedReturns read answers per order, with no event date to window on.
String windowFieldValue = ValueSupport.normalize(windowFieldName)
if (windowFieldValue && (windowFieldValue != "createdAt" || completedReturns)) {
    errors = [(windowFieldValue != "createdAt"
            ? "Shopify return refs can be windowed by createdAt (the refund's or the return's own), not ${windowFieldValue}."
            : "Shopify completedReturns reads are per order and have no event createdAt to window on.").toString()]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}
// DAR-BE-320: count every call this extract makes to Shopify (requestMetadata.shopifyCalls).
ShopifyCallTally.start()

Map<String, Object> resolved = ShopifyExtractSupport.resolveConfigAndWindow(ec, shopifyAuthConfigId, companyUserGroupId,
        windowStart, windowEnd, "SHOPIFY_RETURN_REFS")
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

Map<String, Object> window = ShopifyExtractSupport.resolveSourceWindow(ec, authConfig,
        (Timestamp) resolved.windowStartValue, (Timestamp) resolved.windowEndValue, preserveWindowInstants)
Timestamp sourceWindowStart = (Timestamp) window.start
Timestamp sourceWindowEnd = (Timestamp) window.end

// CURSOR path, not Bulk Operations: refunds/returns are GraphQL connections and the bulk path
// rejects connection-bearing fields (see ShopifyReturnRefsSupport javadoc). All query building,
// pagination, and per-order id collection live there; this edge only resolves the tenant config,
// normalizes the window (above, shared with extractShopifyOrders through ShopifyExtractSupport),
// calls the support class, and writes the file.
//
// pageSize has no matching DEFAULT_PAGE_SIZE constant on ShopifyReturnRefsSupport (only
// DEFAULT_CONNECTION_PAGE_SIZE exists there) — the service parameter's own default-value="100"
// already covers the normal case, and ShopifyGraphqlQueryBuilder.buildQuery falls back to
// ShopifySourceCatalog's SHOPIFY_ORDER_RETURN_REFS.defaultPageSize (also 100) if a null ever
// reaches it. ValueSupport.normalizeInt(pageSize, 100) is defensive parity with that same default,
// not a fabricated constant reference.
// DAR-BE-137: completedReturns asks a different question of the same source — one row per order with a return
// completed in the window (refunded, exchanged or orderRefunded), for a tenant whose returns are checked in NetSuite.
Map extraction = completedReturns ? ShopifyCompletedReturnsSupport.extract(ShopifyAuthConfigSupport.transportConfig(authConfig),
        completedReturns as String, sourceWindowStart.time, sourceWindowEnd.time,
        [connectTimeoutMillis: connectTimeoutMillis, readTimeoutMillis: readTimeoutMillis, maxAttempts: maxAttempts])
        : ShopifyReturnRefsSupport.extractReturnRefs(ShopifyAuthConfigSupport.transportConfig(authConfig),
        window.startText, window.endText, [
        pageSize            : ValueSupport.normalizeInt(pageSize, 100),
        connectionPageSize  : ValueSupport.normalizeInt(connectionPageSize, ShopifyReturnRefsSupport.DEFAULT_CONNECTION_PAGE_SIZE),
        // Important #3 (fix-wave-C): widens the fetch/emit floor before windowStart so a Shopify
        // event created just before it is still available for the OMS-side forward match (OMS lags
        // Shopify by ~38min, RQ-23). See ShopifyReturnRefsSupport's class doc.
        lookbackHours       : ValueSupport.normalizeInt(lookbackHours, ShopifyReturnRefsSupport.DEFAULT_LOOKBACK_HOURS),
        // DAR-BE-318: no sourceFilters. Every event is written; Spark Keep sets aside what a run's rules reject.
        connectTimeoutMillis: connectTimeoutMillis,
        readTimeoutMillis   : readTimeoutMillis,
        maxAttempts         : maxAttempts,
], null)

List<Map<String, Object>> records = (List<Map<String, Object>>) (extraction.records ?: [])
outputWarnings.addAll((List) (extraction.warnings ?: []))
outputErrors.addAll((List) (extraction.errors ?: []))
requestMetadata = extraction.requestMetadata ?: [:]
Map<String, Long> shopifyCalls = ShopifyCallTally.stop()
if (shopifyCalls) requestMetadata = (requestMetadata as Map) + [shopifyCalls: shopifyCalls]

if (outputErrors) {
    errors = outputErrors
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}

// Same {records, metadata} envelope and same atomic .partial move as every other extractor, so
// the compare stage reads this file exactly like an orders extract.
String outputBaseLocation = ShopifyExtractSupport.resolveOutputBaseLocation(ec, outputLocation,
        automationExecutionId, automationId, configIdValue)
File outputDirectory = DataManagerSupport.resolveDirectoryFile(ec, outputBaseLocation, true)
File workFile = outputDirectory != null
        ? File.createTempFile("shopify-return-refs-", ".partial", outputDirectory)
        : File.createTempFile("shopify-return-refs-", ".partial")

try {
    workFile.text = JsonOutput.toJson([records: records, metadata: requestMetadata])
    String outputFileName = ShopifyExtractSupport.safeFileName(
            fileName ?: "shopify-return-refs-${sourceWindowStart.time}-${sourceWindowEnd.time}.json",
            "shopify-return-refs.json")
    fileName = outputFileName
    fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
    DataManagerSupport.moveIntoLocation(ec, workFile, fileLocation as String)
    fileTypeEnumId = "DftJson"
    recordCount = records.size()
    dataAvailable = !records.isEmpty()
    warnings = outputWarnings
    errors = []
} finally {
    if (workFile.exists()) workFile.delete()
}
