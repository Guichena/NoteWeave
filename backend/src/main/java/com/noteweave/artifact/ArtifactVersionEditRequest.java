package com.noteweave.artifact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 产物版本编辑请求。
 *
 * <p>before 由服务端读取，不信任客户端提交的原文；expectedBaseVersionNo 用于
 * 并发 CAS，base 已前进时返回 409。requestId 是 workspace/job 范围内的幂等键。</p>
 */
public record ArtifactVersionEditRequest(
        @NotBlank @Size(max = 128) String requestId,
        int expectedBaseVersionNo,
        @NotBlank @Size(max = 2_000_000) String markdown,
        @Size(max = 500) String editReason
) {
}
