package com.noteweave.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * 请求体里没有被任何字段接收的 JSON 属性。
 *
 * <p>全局命名策略是 snake_case，又沿用默认的忽略未知字段：调用方把可选字段写成驼峰时，请求照样成功、
 * 字段被静默丢弃（研究退回纯网络检索、产物拿不到资料、知识页类型被吞都是这个原因）。</p>
 *
 * <ul>
 *   <li>对外接口（/api/v2）的请求体里，驼峰字段名对应的 snake_case 字段确实存在时直接返回 400，
 *       并提示正确的字段名。这类请求原本就是写错了，前端统一使用 snake_case，不受影响。</li>
 *   <li>其余未知字段仍然忽略，只记 WARN 日志和指标 noteweave.api.unknown_property，避免多传字段的
 *       调用方被误伤。</li>
 * </ul>
 *
 * <p>只检查 HTTP 请求体本身：请求处理中读取的库内存量 JSON（例如历史生成契约）不受影响。</p>
 */
@Configuration
public class UnknownJsonPropertyAudit {

    private static final Logger log = LoggerFactory.getLogger(UnknownJsonPropertyAudit.class);

    /** 当前线程是否正在反序列化 HTTP 请求体。 */
    private static final ThreadLocal<Boolean> READING_REQUEST_BODY = new ThreadLocal<>();

    @Bean
    Jackson2ObjectMapperBuilderCustomizer unknownJsonPropertyAuditCustomizer(ObjectProvider<MeterRegistry> meters) {
        return builder -> builder.postConfigurer(mapper -> mapper.addHandler(new Handler(meters)));
    }

    /** 请求结束时一定清理标记，转换失败时 afterBodyRead 不会被调用。 */
    @Bean
    OncePerRequestFilter unknownJsonPropertyFlagCleanupFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                try {
                    chain.doFilter(request, response);
                } finally {
                    READING_REQUEST_BODY.remove();
                }
            }
        };
    }

    /** 标记请求体反序列化的开始与结束。 */
    @ControllerAdvice
    static class RequestBodyMarker extends RequestBodyAdviceAdapter {

        @Override
        public boolean supports(MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
            return true;
        }

        @Override
        public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                               Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
            READING_REQUEST_BODY.set(Boolean.TRUE);
            return inputMessage;
        }

        @Override
        public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                    Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
            READING_REQUEST_BODY.remove();
            return body;
        }

        @Override
        public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                      Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
            READING_REQUEST_BODY.remove();
            return body;
        }
    }

    /** 驼峰写法的字段名：提示调用方改成 snake_case。 */
    public static class FieldNamingException extends JsonMappingException {
        private final String field;
        private final String expected;

        FieldNamingException(JsonParser parser, String field, String expected) {
            super(parser, "字段 " + field + " 应写成 " + expected);
            this.field = field;
            this.expected = expected;
        }

        public String field() {
            return field;
        }

        public String expected() {
            return expected;
        }
    }

    static final class Handler extends DeserializationProblemHandler {

        private final ObjectProvider<MeterRegistry> meters;

        Handler(ObjectProvider<MeterRegistry> meters) {
            this.meters = meters;
        }

        @Override
        public boolean handleUnknownProperty(
                DeserializationContext context,
                JsonParser parser,
                JsonDeserializer<?> deserializer,
                Object beanOrClass,
                String propertyName
        ) throws IOException {
            String path = currentRequestPath();
            if (path == null) {
                return false;
            }
            if (Boolean.TRUE.equals(READING_REQUEST_BODY.get()) && path.startsWith("/api/v2/")) {
                String expected = snakeCase(propertyName);
                if (!expected.equals(propertyName) && deserializer instanceof BeanDeserializerBase bean
                        && bean.findProperty(expected) != null) {
                    throw new FieldNamingException(parser, propertyName, expected);
                }
            }
            String target = beanOrClass instanceof Class<?> type ? type.getSimpleName()
                    : beanOrClass == null ? "" : beanOrClass.getClass().getSimpleName();
            log.warn("Unknown JSON property ignored during request: path={}, type={}, property={}", path, target, propertyName);
            MeterRegistry registry = meters.getIfAvailable();
            if (registry != null) {
                registry.counter("noteweave.api.unknown_property", "property", propertyName).increment();
            }
            // 返回 false 交回 Jackson 按原有配置处理（当前为忽略）
            return false;
        }

        static String snakeCase(String name) {
            return name == null ? "" : name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        }

        private static String currentRequestPath() {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                return attributes.getRequest().getRequestURI();
            }
            return null;
        }
    }

    /** 供测试设置请求体读取标记。 */
    static void markReadingRequestBody(boolean reading) {
        if (reading) {
            READING_REQUEST_BODY.set(Boolean.TRUE);
        } else {
            READING_REQUEST_BODY.remove();
        }
    }
}
