package shopify.reconciliation.automation

import darpan.common.ValueSupport
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationApiWindowSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import groovy.json.JsonOutput
import shopify.facade.settings.ShopifyAuthConfigSupport
import shopify.graphql.ShopifyBulkOperationClient
import shopify.graphql.ShopifyGraphqlQueryBuilder

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The steps the Shopify extract script services share: extractShopifyOrders,
 * extractShopifyOrderLineUnits (both Bulk Operations) and extractShopifyReturnRefs (cursor path).
 *
 * <p>Each script still owns its order of steps, its record assembly and its own early returns
 * (a Moqui script service sets its out-parameters as script bindings, so the returns cannot move
 * here). What lives here is the logic that was copied between them: window parsing and
 * normalization, config resolution, bulk submission, output naming and the side files. Submitting,
 * polling, downloading and writing a bulk operation are shared operational behaviour on purpose -
 * divergence there would be a second thing to maintain for no benefit.</p>
 */
class ShopifyExtractSupport {

    /**
     * Parses one window bound. On a missing or unparseable value the error is appended to
     * {@code errors} and null is returned, so both bounds are always reported together.
     */
    static Timestamp toTimestamp(Object rawValue, String label, List<String> errors) {
        if (rawValue == null) {
            errors.add("${label} is required.")
            return null
        }
        if (rawValue instanceof Timestamp) return (Timestamp) rawValue
        if (rawValue instanceof Date) return new Timestamp(((Date) rawValue).time)
        if (rawValue instanceof Instant) return Timestamp.from((Instant) rawValue)
        if (rawValue instanceof ZonedDateTime) return Timestamp.from(((ZonedDateTime) rawValue).toInstant())
        if (rawValue instanceof OffsetDateTime) return Timestamp.from(((OffsetDateTime) rawValue).toInstant())
        if (rawValue instanceof LocalDateTime) return Timestamp.valueOf((LocalDateTime) rawValue)
        if (rawValue instanceof LocalDate) return Timestamp.from(((LocalDate) rawValue).atStartOfDay().toInstant(ZoneOffset.UTC))

        String text = ValueSupport.normalize(rawValue)
        if (!text) {
            errors.add("${label} is required.")
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

        errors.add("${label} must be a Timestamp, Date, ISO-8601 value, SQL timestamp text, or epoch milliseconds.")
        return null
    }

    static String formatWindow(Timestamp timestamp) {
        return timestamp?.toInstant()?.toString()
    }

    /**
     * Sanitizes an operator-supplied name (childLocation is plain string concatenation and
     * moveIntoLocation REPLACE_EXISTINGs with no traversal guard of its own) and restores the
     * .json suffix fileTypeEnumId="DftJson" asserts.
     */
    static String safeFileName(Object rawName, String fallback) {
        String safeName = DataManagerSupport.safeToken(rawName, fallback)
        return safeName.toLowerCase(Locale.ROOT).endsWith(".json") ? safeName : "${safeName}.json"
    }

    static String safeJsonlFileName(Object rawName, String fallback) {
        String safeName = DataManagerSupport.safeToken(rawName, fallback)
        if (safeName.toLowerCase(Locale.ROOT).endsWith(".jsonl")) return safeName
        return safeName.replaceFirst(/(?i)\.json$/, "") + ".jsonl"
    }

    /**
     * Parses both window bounds, requires the config id and, only when all of that passed,
     * resolves the tenant's auth config for {@code requiredEndpointSystemEnumId}.
     *
     * @return [configIdValue, windowStartValue, windowEndValue, authConfig, errors] - errors holds
     *         the window/config-id errors in that order, then any ec.message errors the auth
     *         resolution raised
     */
    static Map<String, Object> resolveConfigAndWindow(def ec, Object shopifyAuthConfigId, Object companyUserGroupId,
                                                      Object windowStart, Object windowEnd, String requiredEndpointSystemEnumId) {
        List<String> errors = []
        String configIdValue = ValueSupport.normalize(shopifyAuthConfigId)
        String companyUserGroupIdValue = ValueSupport.normalize(companyUserGroupId)
        Timestamp windowStartValue = toTimestamp(windowStart, "windowStart", errors)
        Timestamp windowEndValue = toTimestamp(windowEnd, "windowEnd", errors)
        if (!configIdValue) errors.add("Shopify auth config ID is required.")

        def authConfig = null
        if (!errors) {
            authConfig = ShopifyAuthConfigSupport.requireUsableAuthConfig(ec, configIdValue, [
                    disableAuthz                : true,
                    companyUserGroupId          : companyUserGroupIdValue,
                    requiredEndpointSystemEnumId: requiredEndpointSystemEnumId,
            ])
            if (ec.message.hasError()) errors.addAll((ec.message.getErrors() ?: []) as List<String>)
        }
        return [configIdValue   : configIdValue,
                windowStartValue: windowStartValue,
                windowEndValue  : windowEndValue,
                authConfig      : authConfig,
                errors          : errors]
    }

    /**
     * Normalizes the window in the config's time zone (else the active tenant's): calendar dates by
     * default, exact instants when preserveWindowInstants is set.
     *
     * @return [sourceWindow, start, end, startText, endText]
     */
    static Map<String, Object> resolveSourceWindow(def ec, def authConfig, Timestamp windowStartValue,
                                                   Timestamp windowEndValue, Object preserveWindowInstants) {
        String sourceTimeZone = ValueSupport.normalize(authConfig?.timeZone) ?: TenantAccessSupport.resolveActiveTenantTimeZone(ec)
        boolean preserveWindowInstantsValue = ValueSupport.normalizeBool(preserveWindowInstants, false)
        Map<String, Object> sourceWindow = preserveWindowInstantsValue ?
                ReconciliationApiWindowSupport.preserveExactWindow(windowStartValue, windowEndValue, sourceTimeZone) :
                ReconciliationApiWindowSupport.normalizeCalendarWindow(windowStartValue, windowEndValue, sourceTimeZone)
        Timestamp sourceWindowStart = (Timestamp) sourceWindow.windowStartDate
        Timestamp sourceWindowEnd = (Timestamp) sourceWindow.windowEndDate
        return [sourceWindow: sourceWindow,
                start       : sourceWindowStart,
                end         : sourceWindowEnd,
                startText   : formatWindow(sourceWindowStart),
                endText     : formatWindow(sourceWindowEnd)]
    }

    /**
     * The field selection and search syntax come from the shared source catalog / query builder so
     * the bulk extraction cannot drift from the catalog contract. apiVersion is intentionally not
     * passed: extraction must keep working for configs saved with versions outside the catalog's
     * setup list. Throws when the query cannot be built.
     */
    /** DAR-BE-156: legacy order ids one by-key bulk read searches ("(id:1 OR id:2 ...)"). */
    static final int LOOKUP_IDS_PER_BULK = 250

    static Map<String, Object> buildIdsBulkQuery(String sourceDefinitionId, String operationName, List<String> ids) {
        return ShopifyGraphqlQueryBuilder.buildBulkQuery([
                sourceDefinitionId: sourceDefinitionId,
                operationName     : operationName,
                filters           : [ids: ids.join(",")],
        ])
    }

    static Map<String, Object> buildCreatedAtBulkQuery(String sourceDefinitionId, String operationName,
                                                       String windowStartText, String windowEndText) {
        return ShopifyGraphqlQueryBuilder.buildBulkQuery([
                sourceDefinitionId: sourceDefinitionId,
                operationName     : operationName,
                filters           : [createdAtFrom: windowStartText, createdAtTo: windowEndText],
        ])
    }

    /**
     * Submits, polls and downloads the bulk operation. {@code params} carries the service's
     * transport and bulk knobs (connectTimeoutMillis, readTimeoutMillis, maxAttempts,
     * maxBulkPollAttempts, bulkPollIntervalMillis, bulkStartRetryAttempts, bulkStartRetryDelayMillis);
     * the bulk ones are clamped to their floors here.
     */
    static Map<String, Object> runBulkQuery(def authConfig, String bulkQueryDocument, Map params) {
        Integer bulkMaxPollAttempts = Math.max(1, ValueSupport.normalizeInt(params.maxBulkPollAttempts, ShopifyBulkOperationClient.DEFAULT_MAX_POLL_ATTEMPTS))
        Integer bulkPollDelayMillis = Math.max(0, ValueSupport.normalizeInt(params.bulkPollIntervalMillis, ShopifyBulkOperationClient.DEFAULT_POLL_INTERVAL_MILLIS))
        Integer bulkStartRetryAttemptCount = Math.max(0, ValueSupport.normalizeInt(params.bulkStartRetryAttempts, ShopifyBulkOperationClient.DEFAULT_START_RETRY_ATTEMPTS))
        Integer bulkStartRetryDelayMillisValue = Math.max(0, ValueSupport.normalizeInt(params.bulkStartRetryDelayMillis, ShopifyBulkOperationClient.DEFAULT_START_RETRY_DELAY_MILLIS))

        return ShopifyBulkOperationClient.runQuery(ShopifyAuthConfigSupport.transportConfig(authConfig), bulkQueryDocument, [
                connectTimeoutMillis : params.connectTimeoutMillis,
                readTimeoutMillis    : params.readTimeoutMillis,
                maxAttempts          : params.maxAttempts,
                maxPollAttempts      : bulkMaxPollAttempts,
                pollIntervalMillis   : bulkPollDelayMillis,
                startRetryAttempts   : bulkStartRetryAttemptCount,
                startRetryDelayMillis: bulkStartRetryDelayMillisValue,
        ])
    }

    /** A failed bulk result's errors, blanks dropped; a generic message when it carried none. */
    static List<String> bulkErrors(Map bulkOperationResult) {
        return ((List) (bulkOperationResult.errors ?: ["Shopify bulk operation request failed."]))
                .collect { Object error -> ValueSupport.normalize(error) }
                .findAll { String error -> error }
    }

    /** The explicit outputLocation, else runtime datamanager reconciliation-runs/{run id}/{timestamp}. */
    static String resolveOutputBaseLocation(def ec, Object outputLocation, Object automationExecutionId,
                                            Object automationId, String configIdValue) {
        String timestamp = DataManagerSupport.formatRunTimestamp(ec)
        return ValueSupport.normalize(outputLocation) ?: DataManagerSupport.resolveReconciliationRunLocation(
                ec,
                automationExecutionId ?: automationId ?: configIdValue,
                timestamp
        )
    }

    /**
     * Writes the bulk operation's raw JSONL beside the extract, named after it.
     *
     * @return [rawJsonlFileName, rawJsonlLocation], both null when the result carried no JSONL text
     */
    static Map<String, String> writeRawJsonl(def ec, Map bulkOperationResult, String outputBaseLocation,
                                             String outputFileName, String fallbackJsonlFileName) {
        String rawJsonlText = bulkOperationResult?.jsonlText?.toString() ?: ""
        String rawJsonlLocation = null
        String rawJsonlFileName = null
        if (rawJsonlText) {
            rawJsonlFileName = safeJsonlFileName(outputFileName, fallbackJsonlFileName)
            rawJsonlLocation = DataManagerSupport.childLocation(outputBaseLocation, rawJsonlFileName)
            DataManagerSupport.writeText(ec, rawJsonlLocation, rawJsonlText)
        }
        return [rawJsonlFileName: rawJsonlFileName, rawJsonlLocation: rawJsonlLocation]
    }

    /**
     * DAR-UI-044 / DAR-BE-063: what the source filters dropped, beside the extract, for the conclude
     * pass. Advisory: a write failure becomes a warning, never a failed extract.
     */
    static void writeExcludedSidecar(def ec, Map excludedCollector, String outputBaseLocation,
                                     String outputFileName, List<String> warnings) {
        try {
            ExcludedRecordsSidecar.writeBeside(ec, excludedCollector, outputBaseLocation, outputFileName, null)
        } catch (Exception sidecarError) {
            warnings.add("Excluded-records sidecar not written: ${sidecarError.message}".toString())
        }
    }

    /**
     * The requestMetadata keys every bulk extract reports, in their reported order. Values may be
     * null; the caller appends its own keys and then drops the null ones, so the order is kept.
     *
     * @param passThrough the raw automationId, fileSide, systemEnumId and sourceTypeEnumId parameters
     * @param window a {@link #resolveSourceWindow} result
     * @param rawJsonl a {@link #writeRawJsonl} result
     */
    static Map<String, Object> bulkRequestMetadata(String sourceType, String configIdValue, Map passThrough,
                                                   Map window, String searchQuery, Map bulkOperationResult, Map rawJsonl) {
        Map sourceWindow = (Map) window.sourceWindow
        return [
                sourceType            : sourceType,
                extractionMode        : "BULK_OPERATION_DATE_FILTER",
                graphqlExecutionMode  : "BULK_OPERATION",
                shopifyAuthConfigId   : configIdValue,
                automationId          : ValueSupport.normalize(passThrough.automationId),
                fileSide              : ValueSupport.normalize(passThrough.fileSide),
                systemEnumId          : ValueSupport.normalize(passThrough.systemEnumId),
                sourceTypeEnumId      : ValueSupport.normalize(passThrough.sourceTypeEnumId),
                sourceTimeZone        : sourceWindow.timeZone,
                calendarDateNormalized: sourceWindow.calendarDateNormalized,
                exactWindowPreserved  : sourceWindow.exactWindowPreserved,
                filterFields          : ["created_at"],
                searchQueries         : [searchQuery],
                windowStartUtc        : window.startText,
                windowEndUtc          : window.endText,
                bulkOperation         : bulkOperationResult.bulkOperation,
                bulkPollCount         : bulkOperationResult.pollCount,
                bulkStatusHistory     : bulkOperationResult.statusHistory,
                bulkStartRetryCount   : bulkOperationResult.startRetryCount,
                bulkStartRetryHistory : bulkOperationResult.startRetryHistory,
                bulkJsonlLineCount    : bulkOperationResult.jsonlLineCount,
                rawJsonlFileName      : rawJsonl.rawJsonlFileName,
                rawJsonlLocation      : rawJsonl.rawJsonlLocation,
        ] as Map<String, Object>
    }

    /** The {metadata, records} envelope the compare stage reads. */
    static void writeExtractFile(def ec, String fileLocation, Map requestMetadata, List records) {
        DataManagerSupport.writeText(ec, fileLocation, JsonOutput.toJson([
                metadata: requestMetadata,
                records : records,
        ]))
    }
}
