package com.noteweave.config;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tomcat 在进入 Spring 之前就拒绝的请求（例如查询参数里直接带未编码的中文），原来返回一张 HTML 错误页，
 * 与所有接口统一的 JSON 错误结构不一致，调用方解析失败。这里把 Tomcat 层的错误页也改成 JSON。
 */
public class JsonErrorReportValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        String code = status == 400 ? "REQUEST_REJECTED" : "HTTP_" + status;
        String message = status == 400
                ? "请求无法解析：请求地址或参数包含未编码的字符，请对查询参数做 URL 编码后重试"
                : "请求处理失败";
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write("{\"success\":false,\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}");
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException ignored) {
            // 响应已经无法写入时保持 Tomcat 的默认处理
        }
    }

    @Configuration
    static class Registration {
        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> jsonErrorReportValveCustomizer() {
            return factory -> factory.addContextCustomizers(context -> {
                if (context.getParent() instanceof org.apache.catalina.core.StandardHost host) {
                    host.setErrorReportValveClass(JsonErrorReportValve.class.getName());
                }
            });
        }
    }
}
