package com.noteweave.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class WorkspaceAuthorizationInterceptor implements HandlerInterceptor {

    private static final Pattern WORKSPACE_PATH = Pattern.compile("^/api/v2/workspaces/([^/]+)(?:/.*)?$");
    private static final Pattern KNOWLEDGE_ITEM_PATH = Pattern.compile("^/api/v2/knowledge-items/([^/]+)(?:/.*)?$");
    private static final Pattern MESSAGE_SOURCE_PATH =
            Pattern.compile("^/api/v2/messages/([^/]+)/(save-as-source|source-draft)$");
    private static final Pattern TASK_PATH = Pattern.compile("^/api/v2/tasks/([^/]+)(?:/.*)?$");
    private static final Pattern CONVERSATION_PATH = Pattern.compile("^/api/v2/conversations/([^/]+)/messages$");
    private static final Pattern UPLOAD_PATH = Pattern.compile("^/api/v2/uploads/([^/]+)(?:/.*)?$");
    private static final Pattern CHAT_REQUEST_PATH = Pattern.compile("^/api/v2/chat/requests/([^/]+)/stream$");

    private final WorkspaceAccessGuard guard;

    public WorkspaceAuthorizationInterceptor(WorkspaceAccessGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // NR01：与认证过滤器共用规范化路径；分号矩阵参数/编码变体不再能避开授权正则
        String path = ApiPaths.canonicalPath(request);
        if (path == null) {
            throw new com.noteweave.common.BusinessException(
                    "REQUEST_PATH_INVALID", "请求路径包含非法编码", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        String method = request.getMethod();
        Matcher matcher = WORKSPACE_PATH.matcher(path);
        if (matcher.matches()) {
            guard.requirePermission(matcher.group(1), workspacePermission(path, method));
            return true;
        }
        if ((matcher = KNOWLEDGE_ITEM_PATH.matcher(path)).matches()) {
            WorkspacePermission permission = HttpMethod.GET.matches(method)
                    ? WorkspacePermission.WORKSPACE_READ : WorkspacePermission.KNOWLEDGE_WRITE;
            guard.requireKnowledgeItemPermission(matcher.group(1), permission);
        } else if ((matcher = MESSAGE_SOURCE_PATH.matcher(path)).matches()) {
            guard.requireMessagePermission(matcher.group(1), WorkspacePermission.SOURCE_WRITE);
        } else if ((matcher = TASK_PATH.matcher(path)).matches()) {
            guard.requireTaskPermission(matcher.group(1), WorkspacePermission.WORKSPACE_READ);
        } else if ((matcher = CONVERSATION_PATH.matcher(path)).matches()) {
            guard.requireConversationPermission(matcher.group(1), WorkspacePermission.ANSWER_RUN);
        } else if (!("/api/v2/uploads/formats".equals(path)
                && (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)))
                && (matcher = UPLOAD_PATH.matcher(path)).matches()) {
            guard.requireUploadPermission(matcher.group(1), WorkspacePermission.SOURCE_WRITE);
        } else if ((matcher = CHAT_REQUEST_PATH.matcher(path)).matches()) {
            guard.requireChatRequestPermission(matcher.group(1), WorkspacePermission.WORKSPACE_READ);
        } else if (!explicitlyPublic(path, method)) {
            // NR01：默认拒绝。受保护前缀下未登记授权规则的路由不再静默放行；
            // 新路由必须在上面显式登记资源授权，或确认属于公共端点后加入白名单。
            throw new com.noteweave.common.BusinessException(
                    "WORKSPACE_ROUTE_UNAUTHORIZED",
                    "该接口未登记工作台资源授权，已按默认拒绝处理",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return true;
    }

    /** 显式公共端点：认证入口、工作台创建/列出（任意已认证用户）、只读技能目录、上传格式清单。 */
    private boolean explicitlyPublic(String path, String method) {
        boolean read = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
        if (path.startsWith("/api/v2/auth/")) {
            return true;
        }
        if (path.equals("/api/v2/workspaces")) {
            return read || HttpMethod.POST.matches(method);
        }
        if (path.equals("/api/v2/skills")) {
            return read;
        }
        return path.equals("/api/v2/uploads/formats") && read;
    }

    private WorkspacePermission workspacePermission(String path, String method) {
        if (path.contains("/members")) {
            return WorkspacePermission.WORKSPACE_ADMIN;
        }
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)) {
            return WorkspacePermission.WORKSPACE_READ;
        }
        if (path.endsWith("/wiki-settings")) {
            return WorkspacePermission.WORKSPACE_ADMIN;
        }
        if (path.contains("/uploads") || path.contains("/sources")) {
            return WorkspacePermission.SOURCE_WRITE;
        }
        if (path.contains("/answer-runs")) {
            return WorkspacePermission.ANSWER_RUN;
        }
        if (path.contains("/conversations")) {
            return WorkspacePermission.ANSWER_RUN;
        }
        if (path.contains("/knowledge-items") || path.contains("/wiki/")) {
            return WorkspacePermission.KNOWLEDGE_WRITE;
        }
        if (path.contains("/memory/promotions")) {
            return WorkspacePermission.MEMORY_REVIEW;
        }
        if (path.contains("/memory/signals")) {
            return WorkspacePermission.ANSWER_RUN;
        }
        // SEC-05：记忆路由显式登记。此前除 promotions/signals 之外的记忆写接口
        // 都落到最后的 WORKSPACE_ADMIN 兜底，编辑者按服务层权限能做、按路由权限却被拦。
        if (path.contains("/memory/")) {
            return WorkspacePermission.MEMORY_REVIEW;
        }
        if (path.contains("/research-runs") || path.contains("/artifact-jobs")
                || path.contains("/video-learning-bundles")) {
            return WorkspacePermission.EXECUTION_OPERATE;
        }
        return WorkspacePermission.WORKSPACE_ADMIN;
    }
}
