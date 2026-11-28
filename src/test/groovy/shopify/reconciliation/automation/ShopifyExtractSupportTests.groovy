package shopify.reconciliation.automation

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S42a (I5 determinism). A window bound written without a zone is a UTC instant (the "UTC midnight,
 * reinterpreted through the config's zone" convention resolveSourceWindow applies next), whatever zone the JVM runs in.
 */
class ShopifyExtractSupportTests {

    private static Timestamp parse(Object raw, List<String> errors = []) {
        return ShopifyExtractSupport.toTimestamp(raw, "windowStart", errors)
    }

    @Test
    void aZonelessBoundIsUtcWhateverTheJvmZone() {
        TimeZone saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            Instant midnightUtc = Instant.parse("2026-11-01T00:00:00Z")
            assertEquals(midnightUtc, parse("2026-11-01 00:00:00").toInstant(), "SQL timestamp text")
            assertEquals(midnightUtc, parse("2026-11-01T00:00").toInstant(), "ISO local date-time text")
            assertEquals(midnightUtc, parse(LocalDateTime.of(2026, 11, 1, 0, 0)).toInstant(), "LocalDateTime")
            assertEquals(midnightUtc, parse(LocalDate.of(2026, 11, 1)).toInstant(), "LocalDate")
            assertEquals(midnightUtc, parse("2026-11-01").toInstant(), "ISO date text")
            // A wall time New York skips (DST gap) is still that time in UTC.
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(Instant.parse("2026-03-08T02:30:00Z"), parse("2026-03-08 02:30:00").toInstant(), "DST-gap SQL text")
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    void aBoundThatNamesItsZoneOrIsAnInstantKeepsIt() {
        assertEquals(Instant.parse("2026-10-31T18:30:00Z"), parse("2026-11-01T00:00:00+05:30").toInstant())
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), parse("2026-11-01T00:00:00Z").toInstant())
        assertEquals(Instant.ofEpochMilli(1793491200000L), parse("1793491200000").toInstant())
    }

    /** OC115: the metadata says which field the bulk search matched, and a read by key is a lookup, not a date filter. */
    @Test
    void bulkMetadataReportsTheFieldTheSearchMatched() {
        Map window = [sourceWindow: [timeZone: "UTC"], startText: "2026-11-01T00:00:00Z", endText: "2026-11-02T00:00:00Z"]
        Map byDate = ShopifyExtractSupport.bulkRequestMetadata("SHOPIFY_GRAPHQL_ORDERS", "C", [:], window, "q", [:], [:])
        assertEquals(["created_at"], byDate.filterFields)
        assertEquals("BULK_OPERATION_DATE_FILTER", byDate.extractionMode)
        Map byUpdate = ShopifyExtractSupport.bulkRequestMetadata("SHOPIFY_GRAPHQL_ORDERS", "C", [:], window, "q", [:], [:], "updated_at")
        assertEquals(["updated_at"], byUpdate.filterFields)
        assertEquals("BULK_OPERATION_DATE_FILTER", byUpdate.extractionMode)
        Map byId = ShopifyExtractSupport.bulkRequestMetadata("SHOPIFY_GRAPHQL_ORDERS", "C", [:], window, "id IN (2 ids)", [:], [:], "id")
        assertEquals(["id"], byId.filterFields)
        assertEquals("BULK_OPERATION_ID_LOOKUP", byId.extractionMode)
    }

    @Test
    void aMissingOrUnreadableBoundIsReportedNotGuessed() {
        List<String> errors = []
        assertNull(parse(null, errors))
        assertNull(parse("not a date", errors))
        assertEquals(2, errors.size())
        assertTrue(errors[1].contains("must be a Timestamp"))
    }
}
