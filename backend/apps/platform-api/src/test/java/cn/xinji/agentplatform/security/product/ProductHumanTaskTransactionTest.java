package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling;
import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class ProductHumanTaskTransactionTest {
    private static final String ISSUER = "https://identity.example.org";
    private static final String AUDIENCE = "https://api.example.org";
    final MutableClock clock = new MutableClock();
    JdbcDataSource source;
    JdbcTemplate db;
    JdbcTemplate authority;
    DataSourceTransactionManager manager;
    ProductGrantWatermark watermark;
    ProductConsultationAccess access;
    private ProductSigningKeys keys;
    JwtDecoder decoder;
    ProductHumanTaskTransaction transaction;
    ProductTaskDirectory.Task task;
    String token;
    private String sid;
    private String grant;

    @BeforeEach void setup() throws Exception {
        source = source();
        JdbcDataSource independent = source();
        migrate(source, "dm8/V032__product_identity.sql", "dm8/V033__product_oauth2.sql",
                "dm8/V034__product_consultation_initial_task.sql",
                "dm8/V041__product_task_authorization_version.sql", "dm8/V042__product_consultation_goal.sql",
                "dm8/V046__product_workload_subject.sql", "dm8/V057__product_oauth_client_control.sql");
        migrate(independent, "identity-authority/V001__grant_watermark.sql");
        db = new JdbcTemplate(source);
        authority = new JdbcTemplate(independent);
        manager = new DataSourceTransactionManager(source);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", ISSUER,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,"
                + "created_at) VALUES(?,'member','member','encoded','ACTIVE',1,CURRENT_TIMESTAMP)", ISSUER);
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        keys = new ProductSigningKeys(db, new TransactionTemplate(manager), ISSUER,
                Base64.getEncoder().encodeToString(master), Duration.ofMinutes(5), clock);
        String kid = keys.rotate("maintainer");
        db.update("INSERT INTO oauth2_registered_client(id,client_id,client_id_issued_at,client_name,"
                + "client_authentication_methods,authorization_grant_types,redirect_uris,scopes,"
                + "client_settings,token_settings) VALUES('registered','public-client',CURRENT_TIMESTAMP,"
                + "'Workbench','none','authorization_code','https://api.example.org/callback','openid','{}','{}')");
        db.update("INSERT INTO id2_client_current VALUES('public-client','registered',"
                + "'https://api.example.org/callback','ACTIVE',1,CURRENT_TIMESTAMP)");
        sid = UUID.randomUUID().toString();
        token = token("member", sid, kid);
        var directory = new JdbcProductTaskDirectory(db, clock);
        task = directory.insertInitial(new ProductTaskDirectory.Initial("organization", "Organization", ISSUER,
                "member", "approved-policy", 1, "a".repeat(64), clock.instant().plusSeconds(3600),
                "member", "initial", "a".repeat(64)));
        db.update("UPDATE con3_task SET state='IN_PROGRESS',version=7 WHERE task_id=?", task.id());
        task = directory.find(task.id()).orElseThrow();
        watermark = new ProductGrantWatermark(db, authority, ISSUER);
        var grants = new ProductGrants(db, new TransactionTemplate(manager), clock);
        grant = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, "member", task.organization(),
                "CONSULTATION", task.id(), task.dataScope(), "TASK", task.id(), 1, "WRITE", "TASK_WORK"),
                clock.instant().plusSeconds(3600), 0, "maintainer", "write", "b".repeat(64))).grantId();
        access = new ProductConsultationAccess(db, watermark, ISSUER, clock);
        decoder = new ProductIssuerConfiguration(new MockEnvironment()).jwtDecoder(keys, db, watermark, ISSUER, AUDIENCE);
        transaction = new ProductHumanTaskTransaction(db, manager, watermark, access, decoder, ISSUER, clock);
    }

    @Test void commitsWithPersistedScopeAuthorizationVersionAndActualActor() {
        var result = transaction.execute(token, task.id(), context -> {
            assertThat(context.task()).isEqualTo(task);
            assertThat(context.task().version()).isEqualTo(7);
            assertThat(context.task().authorizationVersion()).isEqualTo(1);
            assertThat(context.actor().subject()).isEqualTo("member");
            write();
            return context;
        });
        assertThat(result.task().dataScope()).isEqualTo(task.dataScope());
        assertThat(writes()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"session", "subject", "client", "key", "task", "scope", "grant", "expiry", "pending"})
    void terminalChangeRollsBackEveryCallbackWrite(String change) {
        assertThatThrownBy(() -> transaction.execute(token, task.id(), context -> {
            write();
            switch (change) {
                case "session" -> db.update("UPDATE id2_session SET status='REVOKED' WHERE sid=?", sid);
                case "subject" -> db.update("UPDATE id2_subject SET status='DISABLED' WHERE subject_id='member'");
                case "client" -> db.update("UPDATE id2_client_current SET status='REVOKED'");
                case "key" -> db.update("UPDATE id2_signing_key SET status='REVOKED'");
                case "task" -> db.update("UPDATE con3_task SET version=8 WHERE task_id=?", task.id());
                case "scope" -> db.update("UPDATE con3_data_scope SET scope_version=2 WHERE task_id=?", task.id());
                case "grant" -> db.update("UPDATE id2_grant SET status='REVOKED' WHERE grant_id=?", grant);
                case "expiry" -> clock.now = task.expiresAt();
                case "pending" -> authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='intent'");
                default -> throw new AssertionError(change);
            }
            return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(writes()).isZero();
        assertThat(new JdbcProductTaskDirectory(db, clock).find(task.id())).contains(task);
        assertThat(db.queryForObject("SELECT status FROM id2_session WHERE sid=?", String.class, sid)).isEqualTo("ACTIVE");
    }

    @Test void callbackFailureRollsBack() {
        assertThatThrownBy(() -> transaction.execute(token, task.id(), context -> {
            write();
            throw new IllegalArgumentException("callback failure");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(writes()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"other", "service", "logout", "scope", "expiry", "awaiting", "unregistered"})
    void inadmissibleFactsNeverEnterCallback(String change) {
        String candidate = token;
        switch (change) {
            case "other" -> {
                db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,"
                        + "created_at) VALUES(?,'other','other','encoded','ACTIVE',1,CURRENT_TIMESTAMP)", ISSUER);
                candidate = token("other", UUID.randomUUID().toString(), decoder.decode(token).getHeaders().get("kid").toString());
            }
            case "service" -> db.update("UPDATE id2_subject SET subject_kind='SERVICE',password_hash=NULL WHERE subject_id='member'");
            case "logout" -> db.update("UPDATE id2_session SET status='REVOKED' WHERE sid=?", sid);
            case "scope" -> db.update("UPDATE con3_data_scope SET scope_version=2 WHERE task_id=?", task.id());
            case "expiry" -> clock.now = task.expiresAt();
            case "awaiting" -> db.update("UPDATE con3_task SET state='AWAITING_GOAL' WHERE task_id=?", task.id());
            case "unregistered" -> db.update("UPDATE id2_session SET client_id='public-client' WHERE sid=?", sid);
            default -> throw new AssertionError(change);
        }
        String original = candidate;
        assertThatThrownBy(() -> transaction.execute(original, task.id(), context -> { write(); return null; }))
                .isInstanceOf(RuntimeException.class);
        assertThat(writes()).isZero();
    }

    @Test void forgedSignatureNeverEntersCallback() {
        String[] parts = token.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        assertThatThrownBy(() -> transaction.execute(String.join(".", parts), task.id(), context -> {
            write(); return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(writes()).isZero();
    }

    @Test void logoutAfterBusinessLockWaitsUntilBusinessCommit() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            CountDownLatch started = new CountDownLatch(1);
            var future = transaction.execute(token, task.id(), context -> {
                write();
                var logout = pool.submit(() -> {
                    started.countDown();
                    db.update("UPDATE id2_session SET status='REVOKED' WHERE sid=?", sid);
                });
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> logout.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                return logout;
            });
            future.get(5, TimeUnit.SECONDS);
            assertThat(writes()).isEqualTo(1);
            assertThatThrownBy(() -> transaction.execute(token, task.id(), context -> null)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void outerTransactionAndDifferentMainBindingsAreRejected() {
        new TransactionTemplate(manager).executeWithoutResult(status ->
                assertThatThrownBy(() -> transaction.execute(token, task.id(), context -> null))
                        .isInstanceOf(IllegalStateException.class));
        assertThatThrownBy(() -> new ProductHumanTaskTransaction(db, new DataSourceTransactionManager(source()),
                watermark, access, decoder, ISSUER, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductHumanTaskTransaction(new JdbcTemplate(authority.getDataSource()), manager,
                watermark, access, decoder, ISSUER, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductHumanTaskTransaction(db, manager, watermark,
                new ProductConsultationAccess(authority, watermark, ISSUER, clock), decoder, ISSUER, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sameDatabaseAuthorityEvenThroughAnotherPoolIsRejected() throws Exception {
        migrate(source, "identity-authority/V001__grant_watermark.sql");
        db.update("INSERT INTO id2_authority_watermark VALUES(?,1,'READY',NULL,?)", ISSUER,
                authority.queryForObject("SELECT history_sha FROM id2_authority_watermark", String.class));
        JdbcDataSource duplicate = new JdbcDataSource();
        duplicate.setURL(source.getURL());
        duplicate.setUser("SA");
        var same = new ProductGrantWatermark(db, new JdbcTemplate(duplicate), ISSUER);
        assertThatThrownBy(() -> new ProductHumanTaskTransaction(db, manager, same, access, decoder, ISSUER, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sharedInstanceUsesSeparateSchemaAndAccountWithoutChangingTheProtocol() throws Exception {
        db.execute("CREATE USER AUTH_RUNTIME PASSWORD 'component-password'");
        db.execute("CREATE SCHEMA AUTH_DATA AUTHORIZATION AUTH_RUNTIME");
        var authorityPool = new JdbcDataSource();
        try (var connection = source.getConnection()) {
            authorityPool.setURL(connection.getMetaData().getURL());
        }
        authorityPool.setUser("AUTH_RUNTIME");
        authorityPool.setPassword("component-password");
        var scoped = new AbstractDataSource() {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = authorityPool.getConnection();
                connection.setSchema("AUTH_DATA");
                return connection;
            }
            @Override public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException {
                throw new java.sql.SQLException("Explicit pool identity required");
            }
        };
        migrate(scoped, "identity-authority/V001__grant_watermark.sql");
        var separate = new JdbcTemplate(scoped);
        separate.update("INSERT INTO id2_authority_watermark VALUES(?,1,'READY',NULL,?)", ISSUER,
                authority.queryForObject("SELECT history_sha FROM id2_authority_watermark", String.class));
        var shared = new ProductGrantWatermark(db, separate, ISSUER);
        var sharedAccess = new ProductConsultationAccess(db, shared, ISSUER, clock);
        var sharedDecoder = new ProductIssuerConfiguration(new MockEnvironment()).jwtDecoder(keys, db, shared, ISSUER, AUDIENCE);
        var accepted = new ProductHumanTaskTransaction(db, manager, shared, sharedAccess, sharedDecoder, ISSUER, clock);
        accepted.execute(token, task.id(), context -> { write(); return null; });
        assertThat(writes()).isEqualTo(1);
        assertThat(separate.queryForObject("SELECT generation FROM id2_authority_watermark", Long.class)).isEqualTo(1);
    }

    @Test void requestStoreCommitsExactClosureAndReplaysBeforeNewTaskVersion() throws Exception {
        var store = requestStore();
        var key = requestKey();
        var original = transaction.execute(token, task.id(), context -> store.save(context, manifest(context), key));
        db.update("UPDATE con3_task SET version=8 WHERE task_id=?", task.id());
        var replay = transaction.execute(token, task.id(), context -> store.replay(context, key).orElseThrow());
        assertThat(replay).isEqualTo(original);
        assertThat(replay.packet().requester().subject()).isEqualTo("member");
        assertThat(db.queryForObject("SELECT state FROM con6_human_handling", String.class)).isEqualTo("WAITING");
        for (String table : List.of("package", "handling", "event", "receipt"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM con6_human_" + table, Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> transaction.execute(token, task.id(), context ->
                store.replay(context, new ProductHumanRequestStore.Key(key.requestId(), "f".repeat(64)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"packageSha", "taskVersion", "scope", "time", "handling", "event", "receipt", "expired"})
    void requestStoreRejectsBrokenNativeClosureAndExpiry(String change) throws Exception {
        var store = requestStore();
        var key = requestKey();
        transaction.execute(token, task.id(), context -> store.save(context, manifest(context), key));
        switch (change) {
            case "packageSha" -> db.update("UPDATE con6_human_package SET manifest_sha=?", "f".repeat(64));
            case "taskVersion" -> db.update("UPDATE con6_human_package SET task_version=99");
            case "scope" -> db.update("UPDATE con6_human_package SET data_scope='other'");
            case "time" -> db.update("UPDATE con6_human_package SET created_at=?", Timestamp.from(clock.instant().minusSeconds(1)));
            case "handling" -> db.update("DELETE FROM con6_human_handling");
            case "event" -> db.update("UPDATE con6_human_event SET request_sha=?", "f".repeat(64));
            case "receipt" -> db.update("UPDATE con6_human_receipt SET audit_until=?", Timestamp.from(clock.instant().plusSeconds(50)));
            case "expired" -> clock.now = clock.instant().plusSeconds(100);
            default -> throw new AssertionError(change);
        }
        assertThatThrownBy(() -> transaction.execute(token, task.id(), context -> store.replay(context, key)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void requestStoreBoundTransactionAndContextAreRequired() throws Exception {
        var store = requestStore();
        var context = new ProductHumanTaskTransaction.Context(task, new OrdinaryHumanHandling.Actor(
                ISSUER, "member", OrdinaryHumanHandling.ActorKind.HUMAN));
        assertThatThrownBy(() -> store.save(context, manifest(context), requestKey())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.execute(token, task.id(), current -> {
            var m = manifest(current);
            var wrong = new ProductHumanRequestStore.Manifest(m.taskId(), m.organization(), "other", m.taskVersion(),
                    m.packageId(), m.packageRevision(), m.requester(), m.binding(), m.references(), m.reason(),
                    m.purpose(), m.policy(), m.bounds(), m.createdAt());
            return store.save(current, wrong, requestKey());
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con6_human_package", Integer.class)).isZero();
    }

    @Test void taskTerminalRefusalRollsBackAllFourRequestTables() throws Exception {
        var store = requestStore();
        assertThatThrownBy(() -> transaction.execute(token, task.id(), context -> {
            store.save(context, manifest(context), requestKey());
            db.update("UPDATE id2_session SET status='REVOKED' WHERE sid=?", sid);
            return null;
        })).isInstanceOf(RuntimeException.class);
        for (String table : List.of("package", "handling", "event", "receipt"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM con6_human_" + table, Integer.class)).isZero();
    }

    ProductHumanRequestStore requestStore() throws Exception {
        migrate(source, "dm8/V109__product_human_request.sql", "dm8/V111__product_human_body_access_deadline.sql");
        return new ProductHumanRequestStore(db, new ObjectMapper().findAndRegisterModules(), clock);
    }

    @Test void taskFactsDeriveCurrentCoordinatesAndNeverReturnOriginalText() {
        var facts = taskFacts();
        var snapshot = transaction.execute(token, task.id(), context -> facts.load(context, token));
        assertThat(snapshot.goal().type()).isEqualTo("GOAL");
        assertThat(snapshot.message().sha()).isEqualTo(ProductComparisonJson.hash("original user statement"));
        assertThat(snapshot.binding().factRevision()).contains(snapshot.message().id(), snapshot.message().sha());
        assertThat(snapshot.binding().reportRevision()).isNull();
        assertThat(new ProductComparisonJson(new ObjectMapper().findAndRegisterModules()).canonical(snapshot))
                .doesNotContain("original user statement", "source-not-read");
        assertThatThrownBy(() -> facts.load(new ProductHumanTaskTransaction.Context(task,
                new OrdinaryHumanHandling.Actor(ISSUER, "member", OrdinaryHumanHandling.ActorKind.HUMAN)), token))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"missingGoal", "messageBytes", "query", "sourceScope", "taskVersion",
            "policy", "actor", "receipt", "time", "expired", "readRevoked"})
    void taskFactsRejectBrokenRelationsAndCurrentRead(String change) {
        var facts = taskFacts();
        switch (change) {
            case "missingGoal" -> db.update("DELETE FROM con3_goal_current");
            case "messageBytes" -> db.update("UPDATE con3_message SET raw_text='changed bytes'");
            case "query" -> db.update("UPDATE con3_search_input SET query_text='other'");
            case "sourceScope" -> db.update("UPDATE con3_search_input SET source_scope_json='[\"other\"]'");
            case "taskVersion" -> db.update("UPDATE con3_goal_current SET task_version=6");
            case "policy" -> db.update("UPDATE con3_message SET retention_evidence_sha=?", "f".repeat(64));
            case "actor" -> {
                db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) "
                        + "VALUES(?,'different','different','encoded','ACTIVE',1,CURRENT_TIMESTAMP)", ISSUER);
                db.update("UPDATE con3_message SET actor_subject='different'");
            }
            case "receipt" -> db.update("DELETE FROM con3_command_receipt");
            case "time" -> db.update("UPDATE con3_search_input SET confirmed_at=?", Timestamp.from(clock.instant().minusSeconds(1)));
            case "expired" -> clock.now = clock.instant().plusSeconds(100);
            case "readRevoked" -> db.update("UPDATE id2_grant SET status='REVOKED' WHERE action_code='READ'");
            default -> throw new AssertionError(change);
        }
        assertThatThrownBy(() -> transaction.execute(token, task.id(), context -> facts.load(context, token)))
                .isInstanceOf(RuntimeException.class);
    }

    ProductHumanTaskFacts taskFacts() {
        return ProductHumanTaskFactsFixture.create(db, manager, watermark, access, decoder, task, clock, ISSUER);
    }
    ProductHumanRequestStore.Key requestKey() { return new ProductHumanRequestStore.Key("original-key", "d".repeat(64)); }
    ProductHumanRequestStore.Manifest manifest(ProductHumanTaskTransaction.Context context) {
        return ProductHumanTaskFactsFixture.manifest(context, clock);
    }

    private String token(String subject, String session, String kid) {
        String jti = UUID.randomUUID().toString();
        Instant now = clock.instant();
        String raw = new NimbusJwtEncoder(keys).encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer(ISSUER).subject(subject).audience(List.of(AUDIENCE)).issuedAt(now).notBefore(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(3600)).id(jti).claim("sid", session).build())).getTokenValue();
        db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)", session, jti, ISSUER, subject, "registered", kid,
                Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)), "ACTIVE", null);
        return raw;
    }

    private void write() {
        db.update("INSERT INTO con3_event VALUES(?,?,'INITIAL_CREATE','member',7,?,CURRENT_TIMESTAMP)",
                "callback", task.id(), "c".repeat(64));
    }
    private int writes() { return db.queryForObject("SELECT COUNT(*) FROM con3_event WHERE event_id='callback'", Integer.class); }
    private static JdbcDataSource source() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        source.setUser("SA");
        return source;
    }
}
