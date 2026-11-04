package com.noteweave.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 记录请求体里没有被任何字段接收的 JSON 属性。
 *
 * <p>全局命名策略是 snake_case，又沿用默认的忽略未知字段：调用方把可选字段写成驼峰或写错名字时，
 * 请求照样成功，字段被静默丢弃（研究退回纯网络检索、产物拿不到资料、知识页类型被吞都是这个原因）。
 * 直接改成拒绝未知字段，前端或 Worker 多传一个字段就会 400，所以先只记录：WARN 日志带接口路径和字段名，
 * 指标 noteweave.api.unknown_property 按字段名计数。观察干净后再对外部接口改为拒绝。</p>
 */
@Configuration
public class UnknownJsonPropertyAudit {

    private static final Logger log = LoggerFactory.getLogger(UnknownJsonPropertyAudit.class);

    @Bean
    Jackson2ObjectMapperBuilderCustomizer unknownJsonPropertyAuditCustomizer(ObjectProvider<MeterRegistry> meters) {
        return builder -> builder.postConfigurer(mapper -> mapper.addHandler(new Handler(meters)));
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
            if (path != null) {
                // 只在 HTTP 请求处理中审计；这里也会覆盖请求期间读取的存量 JSON，日志里的 type 用来区分
                String target = beanOrClass instanceof Class<?> type ? type.getSimpleName()
                        : beanOrClass == null ? "" : beanOrClass.getClass().getSimpleName();
                log.warn("Unknown JSON property ignored during request: path={}, type={}, property={}", path, target, propertyName);
                MeterRegistry registry = meters.getIfAvailable();
                if (registry != null) {
                    registry.counter("noteweave.api.unknown_property", "property", propertyName).increment();
                }
            }
            // 返回 false 交回 Jackson 按原有配置处理（当前为忽略），行为不变
            return false;
        }

        private static String currentRequestPath() {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                return attributes.getRequest().getRequestURI();
            }
            return null;
        }
    }
}
