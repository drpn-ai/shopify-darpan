package shopify.reconciliation.automation

import darpan.dataset.DatasetRefreshSupport
import darpan.dataset.KeptCopyStore
import darpan.dataset.KeptCopySupport
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunExtractSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.source.KeepPlanSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1, Task 4: Shopify return refs kept as a ROLLING copy (a refund or return event each, by the event's
 * own createdAt), on the seeded SHOPIFY_RETURN_REFS connector row plus this component's own declarations on it
 * (data/ShopifyConnectorSeedData.xml). The stub extractor stands in for extract#ShopifyOrderReturnRefs, so no Shopify store
 * is ever called.
 *
 * What the live read returns for a run window [start, end) (ShopifyReturnRefsSupport.extractReturnRefs): the events whose
 * own createdAt lies in [start - lookbackHours, end), lookbackHours 3 by default, the window first rounded to whole days in
 * the store's zone unless the connector keeps the instants. The connector row does not (preserveWindowInstants N), so the
 * source's days are in a zone core does not know, and the run side reads live: ROUNDS_TO_DAYS. The copy is still built, by
 * a dataset read that keeps the instants and the lookback off (datasetReadParametersJson), so its records are exactly the
 * events created in the rolling window.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyReturnRefsRollingCopyTests {

    private static final String A = "KREWE"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String SYSTEM = "SHOPIFY_RETURN_REFS"
    private static final String EXTRACT = "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrderReturnRefs"
    private static final String ID_FIELD = "refundOrReturnType,refundOrReturnId"
    private static final Timestamp START = Timestamp.from(Instant.parse("2026-11-10T08:00:00Z"))
    private static final Timestamp END = Timestamp.from(Instant.parse("2026-11-11T08:00:00Z"))
    private static final Instant COPY_READ_AT = Instant.parse("2026-11-20T00:00:00Z")
    private static final String STAMP = "20261120-000000000"

    private ExecutionContext ec
    private File dataManagerRoot
    private int seq = 0

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "shopify-return-refs-rolling-copy")
        dataManagerRoot = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        assertTrue(dataManagerRoot.path.contains("test-data-manager"), "tests never write the dev datamanager: ${dataManagerRoot}")
        ["datasets", DataManagerSupport.RECONCILIATION_RUNS_PATH].each { new File(dataManagerRoot, it).deleteDir() }

        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/JudgeSeedData.xml", "component://darpan/data/MappingSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/ConclusionCatalogSeedData.xml", "component://darpan/data/DatasetSeedData.xml")
        ec.entity.makeDataLoader().location("component://darpan/data/SourceSystemConnectorSeedData.xml").dummyFks(true).load()
        // this system's own declarations on its rows (ruling P3b1-9)
        ec.entity.makeDataLoader().location("component://shopify-darpan/data/ShopifyConnectorSeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE]).createOrUpdate()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsTenantA() {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in ${EDITOR_USERNAME}")
        ec.user.setPreference(PREF_KEY, A)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    // ----------------------------------------------------------------------------------------------------- the tests

    /** Ruling 3b1-3 / Review Focus 2: a refund and a return can carry one id; they are two records of the copy. */
    @Test
    void aRefundAndAReturnSharingAnIdStayTwoCopyRecords() {
        String id = rollingReturnRefs("rr-shared-id")
        List<Map> events = [event("REFUND", "5550001", "O1", "2026-11-10T09:00:00Z"),
                            event("RETURN", "5550001", "O1", "2026-11-10T09:00:00Z"),
                            event("RETURN", "5550002", "O2", "2026-11-10T10:00:00Z")]
        List<Map> calls = []
        Map res = atCopyClock(0) { refresh(id, stub(calls, events)) }
        assertEquals([], res.errors)
        File dir = DatasetRefreshSupport.datasetDir(ec, A, id)
        List<Map> kept = KeptCopyStore.records(dir).collect { it }
        assertEquals(3, kept.size(), "the refund and the return with one id are not collapsed")
        assertEquals(["REFUND|5550001", "RETURN|5550001", "RETURN|5550002"] as Set, kept*.id as Set)
        assertEquals(3, KeptCopyStore.manifest(dir).recordCount)

        // the next day's whole read changes one of the two: only that one changes, neither is lost
        List<Map> next = [events[0], events[1] + [orderReturnStatus: "RETURNED"], events[2]]
        Map again = atCopyClock(1) { refresh(id, stub([], next)) }
        assertEquals([], again.errors)
        Map byId = KeptCopyStore.records(dir).collectEntries { [(it.id): it.record] }
        assertEquals(3, byId.size())
        assertNull(byId["REFUND|5550001"].orderReturnStatus)
        assertEquals("RETURNED", byId["RETURN|5550001"].orderReturnStatus)
        def read = ec.entity.find("darpan.dataset.DatasetRead").condition("datasetId", id).orderBy("-readSeqId").disableAuthz().useCache(false).list().first()
        assertEquals(1, read.changedCount as int)
        assertNull(read.missCount, "a rolling read counts no misses")

        // a read whose two events collide on type and id is a bad read, never a silent collapse (ruling P3b1-3)
        Map clash = atCopyClock(2) { refresh(id, stub([], [events[0], events[0] + [orderId: "O9"]])) }
        assertTrue(clash.errors.toString().contains("REFUND|5550001"), clash.errors.toString())
    }

    /**
     * The dataset read keeps the instants and the lookback off, by the connector's own declaration, and windows on the
     * event's createdAt. A run side is not served: the live read rounds its window to days and widens it by the lookback
     * (an event 90 minutes before the window start is in the live read), which a slice of the copy cannot reproduce. So
     * the run reads live, with a clear reason, and its file is the live file.
     */
    @Test
    void aReturnRefsRunReadsLiveAndIsNeverServedEvenIfTheConnectorKeepsInstants() {
        def connector = ec.entity.find("darpan.reconciliation.SourceSystemConnector").condition("systemEnumId", SYSTEM)
                .disableAuthz().useCache(false).one()
        assertNotNull(connector)
        assertEquals("N", connector.excludedRecordsSidecar, "this system's declaration: the extract writes no side file")
        assertEquals("N", connector.preserveWindowInstants, "the shared row is not changed by this plan")
        assertNull(connector.windowFieldName)

        String id = rollingReturnRefs("rr-parity")
        List<Map> calls = []
        Map res = atCopyClock(0) { refresh(id, stub(calls, fixture())) }
        assertEquals([], res.errors)
        assertEquals(1, calls.size())
        Map p = calls[0].params
        assertEquals(EXTRACT, calls[0].service)
        assertEquals("createdAt", p.windowFieldName, "the read windows on the event's own date")
        assertEquals(true, p.preserveWindowInstants, "the dataset read keeps the instants it is sent")
        assertEquals(0, p.lookbackHours, "and widens nothing: the copy holds the events of the rolling window")
        assertEquals(COPY_READ_AT.minusSeconds(60 * 86400L), instantOf(p.windowStart), "the whole 60-day window")
        assertEquals(fixture().size(), KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id)).recordCount)

        // the run side, built from the very parameters the interactive path sends
        List<Map> liveCalls = []
        Map run = interactive(liveCalls, "rr-parity", "rr-parity")
        assertEquals([], run.errors)
        assertEquals(1, liveCalls.size(), "the run reads live")
        assertFalse(liveCalls[0].params.containsKey("windowFieldName"), "the live call names no window field")
        Map liveDoc = json(new File(run.result.fileLocation as String))
        assertEquals(true, liveDoc.metadata.live)
        assertNull(liveDoc.metadata.servedFromCopy)
        assertTrue(liveDoc.records*.refundOrReturnId.contains("9000"), "the live read keeps an event just before the window: the 3 h lookback")
        List<String> naive = KeptCopyStore.records(DatasetRefreshSupport.datasetDir(ec, A, id)).findAll { Map r ->
            Instant at = Instant.parse(r.record.createdAt as String)
            !at.isBefore(START.toInstant()) && at.isBefore(END.toInstant())
        }.collect { it.record.refundOrReturnId as String }
        assertFalse(naive.contains("9000"), "a plain cut of the copy by the window would lack it")

        // and the reason is the connector's, not an accident
        Map side = KeptCopySupport.side(SourceSystemConnectorSupport.resolve(ec, SYSTEM),
                [shopifyAuthConfigId: "rr-parity", windowStart: "2026-11-10T08:00:00Z", windowEnd: "2026-11-11T08:00:00Z"],
                [tenantId: A, windowStart: START, windowEnd: END])
        Map served = KeptCopySupport.sliceIfServed(ec, side)
        assertEquals(false, served.served)
        assertEquals(KeptCopySupport.ROUNDS_TO_DAYS, served.reason)

        // ruling P3b1-13: were the connector row flipped to keep instants, the system's own serving check still refuses
        def row = ec.entity.find("darpan.reconciliation.SourceSystemConnector").condition("systemEnumId", SYSTEM).disableAuthz().useCache(false).forUpdate(true).one()
        row.preserveWindowInstants = "Y"
        row.update()
        try {
            Map flipped = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(SourceSystemConnectorSupport.resolve(ec, SYSTEM),
                    [shopifyAuthConfigId: "rr-parity", windowStart: "2026-11-10T08:00:00Z", windowEnd: "2026-11-11T08:00:00Z",
                     preserveWindowInstants: true], [tenantId: A, windowStart: START, windowEnd: END]))
            assertEquals(false, flipped.served)
            assertTrue((flipped.reason as String).contains("widens the window by its lookback"), flipped.reason as String)
            List<Map> calls2 = []
            Map again = interactive(calls2, "rr-flipped", "rr-parity")
            assertEquals([], again.errors)
            assertEquals(1, calls2.size(), "the run still reads live")
        } finally {
            row = ec.entity.find("darpan.reconciliation.SourceSystemConnector").condition("systemEnumId", SYSTEM).disableAuthz().useCache(false).forUpdate(true).one()
            row.preserveWindowInstants = "N"
            row.update()
        }
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    /** The interactive path's own build of the side's parameters; the injected extractor is the live Shopify stub. */
    private Map interactive(List<Map> calls, String token, String conn) {
        Map source = [systemEnumId: SYSTEM, sourceConfigType: "SHOPIFY_RETURN_REFS_API", sourceConfigId: conn, fileSide: "FILE_1"]
        Map artifact = [location: DataManagerSupport.resolveReconciliationRunLocation(ec, "rr-${token}-${++seq}", STAMP), runToken: "parity"]
        ec.message.clearAll()
        Map result = RunExtractSupport.extractApiSource(ec, source, "FILE_1", "File 1", artifact,
                [windowStartDate: START, windowEndDate: END, timeZone: "UTC"], null, liveExtractor(calls), null,
                // DAR-BE-318: the caller owns the side's Keep plan; this test is about the live read, so it is the plain one.
                KeepPlanSupport.todaysRead([]))
        List<String> errors = ec.message.errors.collect { it.toString() }
        ec.message.clearAll()
        return [result: result, errors: errors]
    }

    /**
     * What the live return-refs read answers when it is sent the window: the events created in
     * [windowStart - lookbackHours, windowEnd), the lookback 3 h unless the call says otherwise.
     */
    private Closure liveExtractor(List<Map> calls) {
        return { String service, Map params ->
            calls << [service: service, params: new LinkedHashMap(params)]
            Instant from = instantOf(params.windowStart), to = instantOf(params.windowEnd)
            int lookback = params.containsKey("lookbackHours") ? (params.lookbackHours as int) : ShopifyReturnRefsSupport.DEFAULT_LOOKBACK_HOURS
            Instant floor = from.minusSeconds(lookback * 3600L)
            List<Map> rows = fixture().findAll { Map e ->
                Instant at = Instant.parse(e.createdAt as String)
                !at.isBefore(floor) && at.isBefore(to)
            }
            String fileName = (params.fileName ?: "shopify-return-refs-${from.toEpochMilli()}-${to.toEpochMilli()}.json") as String
            File dir = DataManagerSupport.resolveDirectoryFile(ec, params.outputLocation as String, true)
            new File(dir, fileName).setText(JsonOutput.toJson([metadata: [live: true], records: rows]), "UTF-8")
            return [fileLocation: DataManagerSupport.childLocation(params.outputLocation as String, fileName), fileName: fileName,
                    recordCount: rows.size()]
        }
    }

    /** A refresh's extractor: the given events, as a live extract writes them. */
    private Closure stub(List<Map> calls, List<Map> records) {
        return { String svc, Map p ->
            calls << [service: svc, params: new LinkedHashMap(p)]
            File out = new File(DataManagerSupport.resolveDirectoryFile(ec, p.outputLocation as String, true), p.fileName as String)
            out.setText(JsonOutput.toJson([metadata: [windowStart: p.windowStart, windowEnd: p.windowEnd], records: records]), "UTF-8")
            return [fileLocation: out.absolutePath, recordCount: records.size(), dataAvailable: !records.isEmpty()]
        }
    }

    private String rollingReturnRefs(String conn) {
        Map stored = underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: SYSTEM, sourceConfigId: conn,
                options: [:], idField: ID_FIELD, windowField: "createdAt", readRuleEnumId: "DRR_ROLLING"])
        assertEquals([], stored.errors)
        return stored.out.datasetId as String
    }

    private Map atCopyClock(int plusDays, Closure<Map> work) {
        ec.user.setEffectiveTime(Timestamp.from(COPY_READ_AT.plusSeconds(plusDays * 86400L)))
        try {
            return work.call()
        } finally {
            ec.user.setEffectiveTime(null)
        }
    }

    private Map refresh(String id, Closure extractor) {
        return underFacadeGrant("dataset.DatasetServices.refresh#Dataset", [datasetId: id, extractor: extractor, forceFull: false])
    }

    private static Instant instantOf(Object v) {
        return v instanceof Timestamp ? ((Timestamp) v).toInstant() : Instant.parse(v as String)
    }

    private static Map event(String type, String id, String orderId, String createdAt) {
        return [refundOrReturnId: id, refundOrReturnType: type, orderId: orderId, createdAt: createdAt,
                orderCancelledAt: null, orderReturnStatus: null]
    }

    /** Events around the window [2026-11-10T08:00Z, 2026-11-11T08:00Z): 9000 is 90 minutes before the start, 9004 at the end. */
    private static List<Map> fixture() {
        return [event("REFUND", "9000", "O0", "2026-11-10T06:30:00Z"), event("REFUND", "9001", "O1", "2026-11-10T08:00:00Z"),
                event("RETURN", "9002", "O2", "2026-11-10T15:00:00Z"), event("REFUND", "9003", "O3", "2026-11-11T07:59:59Z"),
                event("REFUND", "9004", "O4", "2026-11-11T08:00:00Z"), event("RETURN", "9005", "O5", "2026-10-02T09:00:00Z"),
                event("REFUND", "9006", "O6", "2026-11-15T12:00:00Z")]
    }

    private static Map json(File f) {
        return (Map) new JsonSlurper().parse(f, "UTF-8")
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                ec.message.clearAll()
                Map out = null
                try {
                    out = ec.service.sync().name(serviceName).parameters(new HashMap(params)).call()
                } catch (Exception e) {
                    ec.message.addError(e.toString())
                }
                List<String> errors = ec.message.errors.collect { it.toString() } +
                        ec.message.validationErrors.collect { it.message?.toString() }
                ec.message.clearAll()
                return [out: errors ? null : out, errors: errors]
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }
}
