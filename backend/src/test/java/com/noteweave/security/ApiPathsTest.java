package com.noteweave.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * NR01：安全层路径规范化。等价路径（分号矩阵参数、百分号编码变体）必须与普通路径
 * 得到同一规范化结果，认证前缀与授权正则不再可被绕过；非法编码返回 null。
 */
class ApiPathsTest {

    @Test
    void semicolonMatrixParamsAreStrippedPerSegment() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "DELETE", "/api;x=1/v2/knowledge-items;v=2/item-1");
        request.setContextPath("");
        assertThat(ApiPaths.canonicalPath(request)).isEqualTo("/api/v2/knowledge-items/item-1");
    }

    @Test
    void percentEncodedSemicolonIsDecodedBeforeStripping() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "DELETE", "/api%3Bx%3D1/v2/knowledge-items/item-1");
        assertThat(ApiPaths.canonicalPath(request)).isEqualTo("/api/v2/knowledge-items/item-1");
    }

    @Test
    void multibyteSegmentsDecodeAsUtf8() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v2/workspaces/ws-%E4%B8%AD%E6%96%87/sources");
        assertThat(ApiPaths.canonicalPath(request)).isEqualTo("/api/v2/workspaces/ws-中文/sources");
    }

    @Test
    void plusSignStaysLiteralInPath() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v2/tasks/task+1");
        assertThat(ApiPaths.canonicalPath(request)).isEqualTo("/api/v2/tasks/task+1");
    }

    @Test
    void malformedPercentEncodingIsRejectedAsNull() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v2/workspaces/%zz");
        assertThat(ApiPaths.canonicalPath(request)).isNull();
        // 截断的百分号序列（不足两位十六进制）同样拒绝
        MockHttpServletRequest truncated = new MockHttpServletRequest(
                "GET", "/api/v2/workspaces/%e");
        assertThat(ApiPaths.canonicalPath(truncated)).isNull();
        // 完整十六进制但非法 UTF-8 的序列解码为替换符：路径不再匹配任何已登记规则，
        // 由拦截器默认拒绝兜底（不在此断言具体替换符形态）
        MockHttpServletRequest invalidUtf8 = new MockHttpServletRequest(
                "GET", "/api/v2/workspaces/%e4");
        assertThat(ApiPaths.canonicalPath(invalidUtf8)).isNotNull();
    }

    @Test
    void plainPathsPassThroughUnchanged() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v2/workspaces/ws-1/wiki-home");
        assertThat(ApiPaths.canonicalPath(request)).isEqualTo("/api/v2/workspaces/ws-1/wiki-home");
    }

    @Test
    void contextPathIsRemovedBeforeNormalization() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/ctx/api;x=1/v2/workspaces/ws-1");
        request.setContextPath("/ctx");
        assertThat(ApiPaths.canonicalPath(request)).isEqualTo("/api/v2/workspaces/ws-1");
    }
}
