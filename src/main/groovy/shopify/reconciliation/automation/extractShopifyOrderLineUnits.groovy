// DAR-BE-050. One record per ORDER LINE UNIT, not per order. Structurally this is
// extractShopifyOrders with the record-assembly step replaced: submitting, polling, downloading
// and writing the bulk operation are shared with it through ShopifyExtractSupport.
import darpan.common.ValueSupport
import darpan.facade.common.DataManagerSupport
import shopify.graphql.ShopifyBulkOperationClient
import shopify.graphql.ShopifySourceCatalog
// Required, not optional: Moqui extract scripts carry no package declaration, so an unimported
// class reference compiles fine (Groovy treats it as a dynamic property) and fails only at RUNTIME.
// A green build proves nothing about it. extractShopifyReturnRefs imports its support class for
// the same reason.
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import shopify.reconciliation.automation.ShopifyExtractSupport
import shopify.reconciliation.automation.ShopifyOrderLineUnitSupport

import java.sql.Timestamp

List<String> outputWarnings = []

Closure<String> normalize = { Object value -> ValueSupport.normalize(value) }
Map<String, Object> resolved = ShopifyExtractSupport.resolveConfigAndWindow(ec, shopifyAuthConfigId, companyUserGroupId,
        windowStart, windowEnd, "SHOPIFY_ORDER_LINE_UNITS")
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
Map<String, Object> builtBulkQuery
try {
    builtBulkQuery = ShopifyExtractSupport.buildCreatedAtBulkQuery(ShopifySourceCatalog.SHOPIFY_ORDER_LINE_UNITS,
            "DarpanShopifyOrderLineUnitsByDateWindow", (String) window.startText, (String) window.endText)
} catch (Exception e) {
    errors = [normalize(e.message) ?: "Shopify bulk query could not be built."]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}
// Configured source filters run CLIENT-side on each emitted UNIT: Shopify's search syntax knows
// nothing of tenant rules, and unitState does not exist until assembly derives it. Parsed here,
// BEFORE the bulk operation is submitted, so a malformed rule fails in seconds rather than after a
// multi-minute bulk run. Empty when unconfigured.
List<Map<String, Object>> parsedSourceFilters
try {
    parsedSourceFilters = ShopifyOrderLineUnitSupport.parseSourceFilters(sourceFilters)
} catch (Exception e) {
    errors = [normalize(e.message) ?: "Configured exclusion rules are invalid."]
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}
String searchQuery = (String) builtBulkQuery.searchQuery
String outputBaseLocation = ShopifyExtractSupport.resolveOutputBaseLocation(ec, outputLocation,
        automationExecutionId, automationId, configIdValue)
File outputDirectory = ShopifyExtractSupport.outputDirectory(ec, outputBaseLocation)
Map<String, Object> bulkOperationResult = ShopifyExtractSupport.runBulkQuery(authConfig,
        (String) builtBulkQuery.queryDocument, context, outputDirectory)

List<Map<String, Object>> records = []
Map assembledUnits = [units: [], orphanLineItemCount: 0, droppedZeroQuantity: 0,
                      unknownStateLineCount: 0, clampedStateLineCount: 0]
Map filteredUnits = [units: [], configuredExclusions: null]
ShopifyExtractSupport.ExtractFileWriter extractWriter = new ShopifyExtractSupport.ExtractFileWriter(outputDirectory)
try {
    if (bulkOperationResult.ok == false) {
        outputErrors.addAll(ShopifyExtractSupport.bulkErrors(bulkOperationResult))
    } else {
        // extractShopifyOrders maps each JSONL line straight to a record, because every line there IS
        // an order. Here the LineItem child lines are the records, so the flat JSONL is assembled into
        // one entry per UNIT. A child line need not sit next to its order, so assembly still reads every
        // line into one list (DAR-BE-162 streamed the download and the write around it, not this).
        List<Map<String, Object>> jsonlRecords = []
        ShopifyBulkOperationClient.eachJsonlRecord((File) bulkOperationResult.jsonlFile) { Map<String, Object> line ->
            jsonlRecords.add(line)
        }
        assembledUnits = ShopifyOrderLineUnitSupport.assembleUnits(jsonlRecords)
        jsonlRecords = null
        // Tested against the assembled UNIT, never the raw JSONL line: a rule may name unitState,
        // which exists only after assembly, or orderReturnStatus, which assembly stamps on every unit.
        filteredUnits = ShopifyOrderLineUnitSupport.applySourceFilters((List) assembledUnits.units, parsedSourceFilters)
        records = (List<Map<String, Object>>) filteredUnits.units
    }
    if (outputErrors) {
        ExcludedRecordsSidecar.discard((Map) filteredUnits?.excludedCollector)
        errors = outputErrors
        warnings = outputWarnings
        dataAvailable = false
        recordCount = records.size()
        return
    }

    String outputFileName = ShopifyExtractSupport.safeFileName(
            fileName ?: "shopify-order-line-units-${sourceWindowStart.time}-${sourceWindowEnd.time}.json",
            "shopify-order-line-units.json"
    )
    Map<String, String> rawJsonl = ShopifyExtractSupport.moveRawJsonl(ec, bulkOperationResult, outputBaseLocation,
            outputFileName, "shopify-order-line-units.jsonl")
    fileName = outputFileName
    fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
    fileTypeEnumId = "DftJson"
    ShopifyExtractSupport.writeExcludedSidecar(ec, (Map) filteredUnits?.excludedCollector, outputBaseLocation,
            outputFileName, outputWarnings)
    recordCount = records.size()
    dataAvailable = records.size() > 0
    Map<String, Object> metadata = ShopifyExtractSupport.bulkRequestMetadata("SHOPIFY_GRAPHQL_ORDER_LINE_UNITS",
            configIdValue, context, window, searchQuery, bulkOperationResult, rawJsonl)
    metadata.putAll([
            extractedRecordCount : records.size(),
            // Reported ALWAYS, not only when non-zero: a unit dropped here vanishes from the Shopify
            // side, and the anti-join then reports it as a genuine difference. An operator reading a
            // run must be able to tell a real finding from a parse loss without opening the JSONL.
            orphanLineItemCount  : assembledUnits.orphanLineItemCount,
            droppedZeroQuantity  : assembledUnits.droppedZeroQuantity,
            // Lines with no currentQuantity / unfulfilledQuantity: their units carry unitState UNKNOWN
            // and an include rule on a state drops them. Always present for the same reason as above.
            unknownStateLineCount: assembledUnits.unknownStateLineCount,
            clampedStateLineCount: assembledUnits.clampedStateLineCount,
            // Absent (not an empty block) when no rules are configured, matching the OMS getters: a
            // block on every extract would read as "a filter always applies". Every configured rule
            // is listed, a zero-count one included.
            filters              : filteredUnits.configuredExclusions != null ?
                    [configuredExclusions: filteredUnits.configuredExclusions] : null,
    ])
    requestMetadata = metadata.findAll { it.value != null } as Map<String, Object>
    int orphanLineItemCount = (assembledUnits.orphanLineItemCount ?: 0) as int
    if (orphanLineItemCount > 0) {
        outputWarnings.add("${orphanLineItemCount} line item(s) had no parent order in the bulk JSONL and were not compared.".toString())
    }
    int unknownStateLineCount = (assembledUnits.unknownStateLineCount ?: 0) as int
    if (unknownStateLineCount > 0) {
        outputWarnings.add("${unknownStateLineCount} line item(s) had no current or unfulfilled quantity; their units carry unitState UNKNOWN.".toString())
    }
    records.each { Map<String, Object> unit -> extractWriter.add(unit) }
    extractWriter.finish(ec, fileLocation as String, requestMetadata)
    warnings = outputWarnings
    errors = []
} finally {
    extractWriter.abort()
    ShopifyExtractSupport.discardBulkResult(bulkOperationResult)
}
