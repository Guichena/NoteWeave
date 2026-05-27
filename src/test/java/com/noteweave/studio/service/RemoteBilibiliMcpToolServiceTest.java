package com.noteweave.studio.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.studio.config.StudioMcpProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RemoteBilibiliMcpToolServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void invokeShouldCallRemoteServiceAndMapResult() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/mcp/bilibili/invoke", exchange -> {
            byte[] response = """
                    {
                      "success": true,
                      "code": "OK",
                      "message": "success",
                      "data": {
                        "toolName": "bilibili",
                        "displayName": "Bilibili MCP",
                        "title": "Remote Bilibili Notes",
                        "promptContext": "Bilibili MCP Tool Result\\nVideo URL: https://www.bilibili.com/video/BV1abc123xyz/",
                        "output": {
                          "url": "https://www.bilibili.com/video/BV1abc123xyz/",
                          "title": "Remote Bilibili Notes",
                          "subtitleLineCount": 12,
                          "pageCount": 1
                        }
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();

        RemoteBilibiliMcpToolService service = new RemoteBilibiliMcpToolService(
                new StudioMcpProperties(
                        new StudioMcpProperties.Bilibili(
                                true,
                                "http://127.0.0.1:" + server.getAddress().getPort(),
                                5
                        )
                ),
                new ObjectMapper()
        );

        StudioMcpTool.ToolResult result = service.invoke(Map.of(
                "url", "https://www.bilibili.com/video/BV1abc123xyz/"
        ));

        assertThat(result.toolName()).isEqualTo("bilibili");
        assertThat(result.title()).isEqualTo("Remote Bilibili Notes");
        assertThat(result.promptContext()).contains("Bilibili MCP Tool Result");
        assertThat(result.output()).containsEntry("pageCount", 1);
    }
}
