package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.HttpMockCenterCatalogClient;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.MockCenterPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MockCenterHttpBoundaryTest {
    private HttpServer server;

    @AfterEach void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test void mapsMetadataSearchAndVersionCheckAcrossHttp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/dev/mock-center/patents/metadata", exchange -> respond(exchange, 200,
                "{\"dataMode\":\"PUBLIC_PILOT\",\"authorityStatus\":\"NON_CENTER_PUBLIC_SOURCE\",\"limitation\":\"test\",\"data\":{\"recordCount\":1,\"catalogVersion\":\"mock-v1\",\"latestImportBatch\":\"batch-1\"}}"));
        server.createContext("/api/dev/mock-center/patents/MCP-1", exchange -> respond(exchange, 200, envelope(record())));
        server.createContext("/api/dev/mock-center/patents/MISSING", exchange -> respond(exchange, 404, "{}"));
        server.createContext("/api/dev/mock-center/patents", exchange -> respond(exchange, 200,
                "{\"dataMode\":\"PUBLIC_PILOT\",\"authorityStatus\":\"NON_CENTER_PUBLIC_SOURCE\",\"limitation\":\"test\",\"data\":{\"items\":[" + record() + "],\"page\":1,\"pageSize\":50,\"total\":1}}"));
        server.start();

        var client = client();
        var adapter = new MockCenterPatentCatalogAdapter(client);
        var result = adapter.search(new PatentSearchCommand(PatentSearchCommand.InputMode.TECHNICAL_SOLUTION,
                "电池分段烘干", "TECHNICAL_REFERENCE", List.of("电池", "分段烘干"),
                PatentSearchCommand.SearchScope.empty()));

        assertEquals("PUBLIC_PILOT", adapter.descriptor().dataMode());
        assertEquals("MCP-1", result.candidates().getFirst().sourceId());
        assertEquals("公开摘要", result.candidates().getFirst().location());
        assertTrue(adapter.isSourceAvailable("MCP-1", "v1"));
        assertFalse(adapter.isSourceAvailable("MCP-1", "v2"));
        assertFalse(adapter.isSourceAvailable("MISSING", "v1"));
    }

    @Test void exposesUpstreamFailureInsteadOfReturningSyntheticSuccess() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/dev/mock-center/patents/metadata", exchange -> respond(exchange, 503, "{}"));
        server.start();
        assertThrows(IllegalStateException.class, () -> client().metadata());
    }

    private HttpMockCenterCatalogClient client() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpMockCenterCatalogClient(url, new ObjectMapper().findAndRegisterModules(),
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private static String envelope(String data) {
        return "{\"dataMode\":\"PUBLIC_PILOT\",\"authorityStatus\":\"NON_CENTER_PUBLIC_SOURCE\",\"limitation\":\"test\",\"data\":" + data + "}";
    }

    private static String record() {
        return "{\"recordId\":\"MCP-1\",\"sourceId\":\"PUBLIC_TEST\",\"sourceRecordId\":\"SRC-1\",\"sourceVersion\":\"v1\",\"publicationNumber\":\"CN-1-A\",\"applicationNumber\":\"CN-1\",\"title\":\"电池极片分段烘干方法\",\"applicants\":[\"测试申请人\"],\"inventors\":[],\"applicationDate\":\"2023-01-01\",\"publicationDate\":\"2024-01-01\",\"ipc\":[\"H01M4/04\"],\"abstractText\":\"电池极片先预烘再分段烘干\",\"abstractState\":\"PARTIAL\",\"sourceUrl\":\"https://example.invalid/CN-1-A\",\"collectedAt\":\"2026-09-17T00:00:00Z\",\"contentDigest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"catalogVersion\":\"mock-v1\"}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
