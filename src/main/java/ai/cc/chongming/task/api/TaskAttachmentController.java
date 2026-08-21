package ai.cc.chongming.task.api;

import ai.cc.chongming.auth.api.PrincipalAccessor;
import ai.cc.chongming.auth.application.JwtTokenService.AuthPrincipal;
import ai.cc.chongming.auth.domain.UserRole;
import ai.cc.chongming.task.application.DevTaskAttachmentService;
import ai.cc.chongming.task.application.DevTaskAttachmentService.AttachmentView;
import ai.cc.chongming.task.domain.DevTaskTypes.DevTaskId;
import ai.cc.chongming.task.domain.DevTaskTypes.TaskAttachmentId;
import ai.cc.chongming.task.domain.exception.TaskDomainException;
import ai.cc.chongming.task.domain.exception.TaskErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * [AIREVIEW-PLAN-031#1] REST surface for task delivery attachments: upload (holder or ADMIN),
 * list/download (any authenticated participant) and delete (uploader or ADMIN).
 *
 * @author wangli
 */
@Validated
@RestController
@RequestMapping("/api/tasks")
public class TaskAttachmentController {

    private final DevTaskAttachmentService attachmentService;
    private final PrincipalAccessor principalAccessor = new PrincipalAccessor();

    public TaskAttachmentController(DevTaskAttachmentService attachmentService) {
        this.attachmentService = Objects.requireNonNull(attachmentService, "attachmentService must not be null");
    }

    @PostMapping("/{taskId}/attachments")
    public AttachmentView upload(
            @PathVariable("taskId") UUID taskId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        AuthPrincipal principal = requirePrincipal(request);
        boolean administrator = UserRole.parse(principal.role()).viewsAllRequirements();
        try {
            return attachmentService.upload(
                    new DevTaskId(taskId),
                    principal.username(),
                    administrator,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getBytes());
        } catch (IOException exception) {
            throw new IllegalStateException("uploaded attachment could not be read", exception);
        }
    }

    @GetMapping("/{taskId}/attachments")
    public List<AttachmentView> list(@PathVariable("taskId") UUID taskId, HttpServletRequest request) {
        requirePrincipal(request);
        return attachmentService.list(new DevTaskId(taskId));
    }

    @GetMapping("/{taskId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> download(
            @PathVariable("taskId") UUID taskId,
            @PathVariable("attachmentId") UUID attachmentId,
            HttpServletRequest request) {
        requirePrincipal(request);
        DevTaskAttachmentService.AttachmentDownload download = attachmentService.download(
                new DevTaskId(taskId), new TaskAttachmentId(attachmentId));
        String encoded = URLEncoder.encode(download.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        MediaType mediaType = parseContentType(download.contentType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(mediaType)
                .contentLength(download.content().length)
                .body(download.content());
    }

    @DeleteMapping("/{taskId}/attachments/{attachmentId}")
    public ResponseEntity<Void> delete(
            @PathVariable("taskId") UUID taskId,
            @PathVariable("attachmentId") UUID attachmentId,
            HttpServletRequest request) {
        AuthPrincipal principal = requirePrincipal(request);
        boolean administrator = UserRole.parse(principal.role()).viewsAllRequirements();
        attachmentService.delete(new DevTaskId(taskId), new TaskAttachmentId(attachmentId),
                principal.username(), administrator);
        return ResponseEntity.noContent().build();
    }

    private MediaType parseContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private AuthPrincipal requirePrincipal(HttpServletRequest request) {
        return principalAccessor.requirePrincipal(request)
                .orElseThrow(() -> new TaskDomainException(TaskErrorCode.FORBIDDEN, "当前请求未携带有效的认证凭据"));
    }
}
