import darpan.common.ValueSupport
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationApiWindowSupport
import groovy.json.JsonOutput
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyBulkOperationClient
import shopify.graphql.ShopifyGraphqlQueryBuilder
import shopify.graphql.ShopifySourceCatalog
import shopify.reconciliation.automation.ShopifyReturnRefsSupport

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
    return normalizedRecord
}
String configIdValue = normalize(shopifyAuthConfigId)
String companyUserGroupIdValue = normalize(companyUserGroupId)
Timestamp windowStartValue = toTimestamp(windowStart, "windowStart")
Timestamp windowEndValue = toTimestamp(windowEnd, "windowEnd")
if (!configIdValue) outputErrors.add("Shopify auth config ID is required.")

def authConfig = null
if (!outputErrors) {
    authConfig = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configIdValue, [
            disableAuthz      : true,
            companyUserGroupId: companyUserGroupIdValue,
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

// CURSOR path, not Bulk Operations: refunds/returns are GraphQL connections and the bulk path
// rejects connection-bearing fields (see ShopifyReturnRefsSupport javadoc). All query building,
// pagination, and per-order id collection live there; this edge only resolves the tenant config,
// normalizes the window (above, identical to extractShopifyOrders), calls the support class, and
// writes the file.
//
// pageSize has no matching DEFAULT_PAGE_SIZE constant on ShopifyReturnRefsSupport (only
// DEFAULT_CONNECTION_PAGE_SIZE exists there) — the service parameter's own default-value="100"
// already covers the normal case, and ShopifyGraphqlQueryBuilder.buildQuery falls back to
// ShopifySourceCatalog's SHOPIFY_ORDER_RETURN_REFS.defaultPageSize (also 100) if a null ever
// reaches it. ValueSupport.normalizeInt(pageSize, 100) is defensive parity with that same default,
// not a fabricated constant reference.
Map extraction = ShopifyReturnRefsSupport.extractReturnRefs([
        shopApiUrl : authConfig.shopApiUrl,
        apiVersion : authConfig.apiVersion,
        accessToken: authConfig.accessToken,
], windowStartText, windowEndText, [
        pageSize            : ValueSupport.normalizeInt(pageSize, 100),
        connectionPageSize  : ValueSupport.normalizeInt(connectionPageSize, ShopifyReturnRefsSupport.DEFAULT_CONNECTION_PAGE_SIZE),
        connectTimeoutMillis: connectTimeoutMillis,
        readTimeoutMillis   : readTimeoutMillis,
        maxAttempts         : maxAttempts,
], null)

List<Map<String, Object>> records = (List<Map<String, Object>>) (extraction.records ?: [])
outputWarnings.addAll((List) (extraction.warnings ?: []))
outputErrors.addAll((List) (extraction.errors ?: []))
requestMetadata = extraction.requestMetadata ?: [:]

if (outputErrors) {
    errors = outputErrors
    warnings = outputWarnings
    dataAvailable = false
    recordCount = 0
    return
}

// Same {records, metadata} envelope and same atomic .partial move as every other extractor, so
// the compare stage reads this file exactly like an orders extract.
String timestamp = DataManagerSupport.formatRunTimestamp(ec)
String outputBaseLocation = outputLocation ?: DataManagerSupport.resolveReconciliationRunLocation(
        ec, automationExecutionId ?: shopifyAuthConfigId, timestamp)
File outputDirectory = DataManagerSupport.resolveDirectoryFile(ec, outputBaseLocation, true)
File workFile = outputDirectory != null
        ? File.createTempFile("shopify-return-refs-", ".partial", outputDirectory)
        : File.createTempFile("shopify-return-refs-", ".partial")

try {
    workFile.text = groovy.json.JsonOutput.toJson([records: records, metadata: requestMetadata])
    String outputFileName = fileName ?: "shopify-return-refs-${sourceWindowStart.time}-${sourceWindowEnd.time}.json".toString()
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
