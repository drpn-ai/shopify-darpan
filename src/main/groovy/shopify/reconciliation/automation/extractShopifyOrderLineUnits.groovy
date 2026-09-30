// DAR-BE-050. One record per ORDER LINE UNIT, not per order. Structurally this is
// extractShopifyOrders with the record-assembly step replaced: submitting, polling, downloading
// and writing the bulk operation are deliberately identical, because those are shared operational
// behaviour and divergence there would be a second thing to maintain for no benefit.
import darpan.common.ValueSupport
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationApiWindowSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import groovy.json.JsonOutput
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyBulkOperationClient
import shopify.graphql.ShopifyGraphqlQueryBuilder
import shopify.graphql.ShopifySourceCatalog
// Required, not optional: Moqui extract scripts carry no package declaration, so an unimported
// class reference compiles fine (Groovy treats it as a dynamic property) and fails only at RUNTIME.
// A green build proves nothing about it. extractShopifyReturnRefs imports its support class for
// the same reason.
import shopify.reconciliation.automation.ShopifyOrderLineUnitSupport

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

List<String> outputErrors = []
List<String> outputWarnings = []

Closure<String> normalize = { Object value -> ValueSupport.normalize(value) }
Closure<Timestamp> toTimestamp = { Object rawValue, String label ->
    if (rawValue == null) {
        outputErrors.add("${label} is required.")
        return null
    }
    if (rawValue instanceof Timestamp) return (Timestamp) rawValue
    if (rawValue instanceof Date) return new Timestamp(((Date) rawValue).time)
    if (rawValue instanceof Instant) return Timestamp.from((Instant) rawValue)
    if (rawValue instanceof ZonedDateTime) return Timestamp.from(((ZonedDateTime) rawValue).toInstant())
    if (rawValue instanceof OffsetDateTime) return Timestamp.from(((OffsetDateTime) rawValue).toInstant())
    if (rawValue instanceof LocalDateTime) return Timestamp.valueOf((LocalDateTime) rawValue)
    if (rawValue instanceof LocalDate) return Timestamp.from(((LocalDate) rawValue).atStartOfDay().toInstant(ZoneOffset.UTC))

    String text = normalize(rawValue)
    if (!text) {
        outputErrors.add("${label} is required.")
        return null
    }
    if (text ==~ /-?\d+/) return new Timestamp(Long.parseLong(text))

    List<Closure<Timestamp>> parsers = [
            { String value -> Timestamp.from(Instant.parse(value)) },
            { String value -> Timestamp.from(OffsetDateTime.parse(value).toInstant()) },
            { String value -> Timestamp.from(ZonedDateTime.parse(value).toInstant()) },
            { String value -> Timestamp.valueOf(value) },
            { String value -> Timestamp.valueOf(LocalDateTime.parse(value)) },
            { String value -> Timestamp.from(LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)) },
    ]
    for (Closure<Timestamp> parser : parsers) {
        try {
            return parser.call(text)
        } catch (Exception ignored) {
        }
    }

    outputErrors.add("${label} must be a Timestamp, Date, ISO-8601 value, SQL timestamp text, or epoch milliseconds.")
    return null
}
Closure<String> formatWindow = { Timestamp timestamp -> timestamp?.toInstant()?.toString() }
Closure<String> safeFileName = { Object rawName, String fallback ->
    String safeName = DataManagerSupport.safeToken(rawName, fallback)
    return safeName.toLowerCase(Locale.ROOT).endsWith(".json") ? safeName : "${safeName}.json"
}
Closure<String> safeJsonlFileName = { Object rawName, String fallback ->
    String safeName = DataManagerSupport.safeToken(rawName, fallback)
    if (safeName.toLowerCase(Locale.ROOT).endsWith(".jsonl")) return safeName
    return safeName.replaceFirst(/(?i)\.json$/, "") + ".jsonl"
}
String configIdValue = normalize(shopifyAuthConfigId)
String companyUserGroupIdValue = normalize(companyUserGroupId)
Timestamp windowStartValue = toTimestamp(windowStart, "windowStart")
Timestamp windowEndValue = toTimestamp(windowEnd, "windowEnd")
if (!configIdValue) outputErrors.add("Shopify auth config ID is required.")

def authConfig = null
if (!outputErrors) {
    authConfig = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configIdValue, [
            disableAuthz                : true,
            companyUserGroupId          : companyUserGroupIdValue,
            requiredEndpointSystemEnumId: "SHOPIFY_ORDER_LINE_UNITS",
    ])
    if (ec.message.hasError()) outputErrors.addAll((ec.message.getErrors() ?: []) as List<String>)
}

if (outputErrors) {
    errors = outputErrors
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}

String sourceTimeZone = normalize(authConfig?.timeZone) ?: TenantAccessSupport.resolveActiveTenantTimeZone(ec)
boolean preserveWindowInstantsValue = ValueSupport.normalizeBool(preserveWindowInstants, false)
Map<String, Object> sourceWindow = preserveWindowInstantsValue ?
        ReconciliationApiWindowSupport.preserveExactWindow(windowStartValue, windowEndValue, sourceTimeZone) :
        ReconciliationApiWindowSupport.normalizeCalendarWindow(windowStartValue, windowEndValue, sourceTimeZone)
Timestamp sourceWindowStart = (Timestamp) sourceWindow.windowStartDate
Timestamp sourceWindowEnd = (Timestamp) sourceWindow.windowEndDate
String windowStartText = formatWindow(sourceWindowStart)
String windowEndText = formatWindow(sourceWindowEnd)
// The field selection and search syntax come from the shared source catalog / query builder so the
// bulk extraction cannot drift from the catalog contract. apiVersion is intentionally not passed:
// extraction must keep working for configs saved with versions outside the catalog's setup list.
Map<String, Object> builtBulkQuery
try {
    builtBulkQuery = ShopifyGraphqlQueryBuilder.buildBulkQuery([
            sourceDefinitionId: ShopifySourceCatalog.SHOPIFY_ORDER_LINE_UNITS,
            operationName     : "DarpanShopifyOrderLineUnitsByDateWindow",
            filters           : [createdAtFrom: windowStartText, createdAtTo: windowEndText],
    ])
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
String bulkQueryDocument = (String) builtBulkQuery.queryDocument
Integer bulkMaxPollAttempts = Math.max(1, ValueSupport.normalizeInt(maxBulkPollAttempts, ShopifyBulkOperationClient.DEFAULT_MAX_POLL_ATTEMPTS))
Integer bulkPollDelayMillis = Math.max(0, ValueSupport.normalizeInt(bulkPollIntervalMillis, ShopifyBulkOperationClient.DEFAULT_POLL_INTERVAL_MILLIS))
Integer bulkStartRetryAttemptCount = Math.max(0, ValueSupport.normalizeInt(bulkStartRetryAttempts, ShopifyBulkOperationClient.DEFAULT_START_RETRY_ATTEMPTS))
Integer bulkStartRetryDelayMillisValue = Math.max(0, ValueSupport.normalizeInt(bulkStartRetryDelayMillis, ShopifyBulkOperationClient.DEFAULT_START_RETRY_DELAY_MILLIS))

Map<String, Object> bulkOperationResult = ShopifyBulkOperationClient.runQuery([
        shopApiUrl : authConfig.shopApiUrl,
        apiVersion : authConfig.apiVersion,
        accessToken: authConfig.accessToken,
], bulkQueryDocument, [
        connectTimeoutMillis: connectTimeoutMillis,
        readTimeoutMillis   : readTimeoutMillis,
        maxAttempts         : maxAttempts,
        maxPollAttempts     : bulkMaxPollAttempts,
        pollIntervalMillis  : bulkPollDelayMillis,
        startRetryAttempts  : bulkStartRetryAttemptCount,
        startRetryDelayMillis: bulkStartRetryDelayMillisValue,
])

List<Map<String, Object>> records = []
Map assembledUnits = [units: [], orphanLineItemCount: 0, droppedZeroQuantity: 0,
                      unknownStateLineCount: 0, clampedStateLineCount: 0]
Map filteredUnits = [units: [], configuredExclusions: null]
if (bulkOperationResult.ok == false) {
    outputErrors.addAll(((List) (bulkOperationResult.errors ?: ["Shopify bulk operation request failed."]))
            .collect { Object error -> normalize(error) }
            .findAll { String error -> error })
} else {
    // extractShopifyOrders maps each JSONL line straight to a record, because every line there IS
    // an order. Here the LineItem child lines are the records, so the flat JSONL is assembled into
    // one entry per UNIT. ShopifyBulkOperationClient.parseJsonlRecords needed no change for this -
    // it already parses every line into a Map, grain-agnostic.
    assembledUnits = ShopifyOrderLineUnitSupport.assembleUnits((List) (bulkOperationResult.records ?: []))
    // Tested against the assembled UNIT, never the raw JSONL line: a rule may name unitState,
    // which exists only after assembly, or orderReturnStatus, which assembly stamps on every unit.
    filteredUnits = ShopifyOrderLineUnitSupport.applySourceFilters((List) assembledUnits.units, parsedSourceFilters)
    records = (List<Map<String, Object>>) filteredUnits.units
}
if (outputErrors) {
    errors = outputErrors
    warnings = outputWarnings
    dataAvailable = false
    recordCount = records.size()
    return
}

String timestamp = DataManagerSupport.formatRunTimestamp(ec)
String outputBaseLocation = normalize(outputLocation) ?: DataManagerSupport.resolveReconciliationRunLocation(
        ec,
        automationExecutionId ?: automationId ?: configIdValue,
        timestamp
)
String outputFileName = safeFileName(
        fileName ?: "shopify-order-line-units-${sourceWindowStart.time}-${sourceWindowEnd.time}.json",
        "shopify-order-line-units.json"
)
String rawJsonlText = bulkOperationResult?.jsonlText?.toString() ?: ""
String rawJsonlLocation = null
String rawJsonlFileName = null
if (rawJsonlText) {
    rawJsonlFileName = safeJsonlFileName(outputFileName, "shopify-order-line-units.jsonl")
    rawJsonlLocation = DataManagerSupport.childLocation(outputBaseLocation, rawJsonlFileName)
    DataManagerSupport.writeText(ec, rawJsonlLocation, rawJsonlText)
}
fileName = outputFileName
fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
fileTypeEnumId = "DftJson"
// DAR-UI-044: what the source filters dropped, beside the extract, for the conclude pass. Advisory.
Map excludedCollector = (Map) filteredUnits?.excludedCollector
if (excludedCollector && ((excludedCollector.total ?: 0) as int) > 0) {
    try {
        DataManagerSupport.writeText(ec, DataManagerSupport.childLocation(outputBaseLocation,
                ExcludedRecordsSidecar.fileNameFor(outputFileName)), ExcludedRecordsSidecar.toJson(excludedCollector, outputFileName))
    } catch (Exception sidecarError) {
        outputWarnings.add("Excluded-records sidecar not written: ${sidecarError.message}".toString())
    }
}
recordCount = records.size()
dataAvailable = records.size() > 0
requestMetadata = [
        sourceType            : "SHOPIFY_GRAPHQL_ORDER_LINE_UNITS",
        extractionMode        : "BULK_OPERATION_DATE_FILTER",
        graphqlExecutionMode  : "BULK_OPERATION",
        shopifyAuthConfigId   : configIdValue,
        automationId          : normalize(automationId),
        fileSide              : normalize(fileSide),
        systemEnumId          : normalize(systemEnumId),
        sourceTypeEnumId      : normalize(sourceTypeEnumId),
        sourceTimeZone        : sourceWindow.timeZone,
        calendarDateNormalized: sourceWindow.calendarDateNormalized,
        exactWindowPreserved  : sourceWindow.exactWindowPreserved,
        filterFields          : ["created_at"],
        searchQueries         : [searchQuery],
        windowStartUtc        : windowStartText,
        windowEndUtc          : windowEndText,
        bulkOperation         : bulkOperationResult.bulkOperation,
        bulkPollCount         : bulkOperationResult.pollCount,
        bulkStatusHistory     : bulkOperationResult.statusHistory,
        bulkStartRetryCount   : bulkOperationResult.startRetryCount,
        bulkStartRetryHistory : bulkOperationResult.startRetryHistory,
        bulkJsonlLineCount    : bulkOperationResult.jsonlLineCount,
        rawJsonlFileName      : rawJsonlFileName,
        rawJsonlLocation      : rawJsonlLocation,
        extractedRecordCount  : records.size(),
        // Reported ALWAYS, not only when non-zero: a unit dropped here vanishes from the Shopify
        // side, and the anti-join then reports it as a genuine difference. An operator reading a
        // run must be able to tell a real finding from a parse loss without opening the JSONL.
        orphanLineItemCount   : assembledUnits.orphanLineItemCount,
        droppedZeroQuantity   : assembledUnits.droppedZeroQuantity,
        // Lines with no currentQuantity / unfulfilledQuantity: their units carry unitState UNKNOWN
        // and an include rule on a state drops them. Always present for the same reason as above.
        unknownStateLineCount : assembledUnits.unknownStateLineCount,
        clampedStateLineCount : assembledUnits.clampedStateLineCount,
        // Absent (not an empty block) when no rules are configured, matching the OMS getters: a
        // block on every extract would read as "a filter always applies". Every configured rule
        // is listed, a zero-count one included.
        filters               : filteredUnits.configuredExclusions != null ?
                [configuredExclusions: filteredUnits.configuredExclusions] : null,
].findAll { it.value != null } as Map<String, Object>
int orphanLineItemCount = (assembledUnits.orphanLineItemCount ?: 0) as int
if (orphanLineItemCount > 0) {
    outputWarnings.add("${orphanLineItemCount} line item(s) had no parent order in the bulk JSONL and were not compared.".toString())
}
int unknownStateLineCount = (assembledUnits.unknownStateLineCount ?: 0) as int
if (unknownStateLineCount > 0) {
    outputWarnings.add("${unknownStateLineCount} line item(s) had no current or unfulfilled quantity; their units carry unitState UNKNOWN.".toString())
}
DataManagerSupport.writeText(ec, fileLocation as String, JsonOutput.toJson([
        metadata: requestMetadata,
        records : records,
]))
warnings = outputWarnings
errors = []
