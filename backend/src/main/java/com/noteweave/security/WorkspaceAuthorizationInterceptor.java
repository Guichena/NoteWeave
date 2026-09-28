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
        String path = request.getRequestURI();
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
        } else if ((matcher = UPLOAD_PATH.matcher(path)).matches()) {
            guard.requireUploadPermission(matcher.group(1), WorkspacePermission.SOURCE_WRITE);
        } else if ((matcher = CHAT_REQUEST_PATH.matcher(path)).matches()) {
            guard.requireChatRequestPermission(matcher.group(1), WorkspacePermission.WORKSPACE_READ);
        }
        return true;
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
        if (path.contains("/research-runs") || path.contains("/artifact-jobs")
                || path.contains("/video-learning-bundles")) {
            return WorkspacePermission.EXECUTION_OPERATE;
        }
        return WorkspacePermission.WORKSPACE_ADMIN;
    }
}
