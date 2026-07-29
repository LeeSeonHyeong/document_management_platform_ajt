package com.ajt.backend.domain.inquiry;

import com.ajt.backend.domain.inquiry.dto.InquiryAnswerRequest;
import com.ajt.backend.domain.inquiry.dto.InquiryAnswerResponse;
import com.ajt.backend.domain.inquiry.dto.InquiryAssigneeListResponse;
import com.ajt.backend.domain.inquiry.dto.InquiryAttachmentDownload;
import com.ajt.backend.domain.inquiry.dto.InquiryCreateRequest;
import com.ajt.backend.domain.inquiry.dto.InquiryListResponse;
import com.ajt.backend.domain.inquiry.dto.InquiryResponse;
import com.ajt.backend.global.auth.AuthenticatedMember;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 문의 API입니다.
 * 사원이 담당자를 직접 선택해 문의를 등록하고, 지정 담당자가 답변합니다.
 */
@RestController
public class InquiryController {

    private final InquiryService inquiryService;

    public InquiryController(InquiryService inquiryService) {
        this.inquiryService = inquiryService;
    }

    /**
     * GET /api/v1/inquiry-assignees
     * 문의 등록 시 선택할 수 있는 관리자 후보를 조회합니다.
     */
    @GetMapping("/api/v1/inquiry-assignees")
    public InquiryAssigneeListResponse assignees(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @RequestParam(name = "keyword", required = false) String keyword
    ) {
        return inquiryService.findAssignees(loginMember, keyword);
    }

    /**
     * POST /api/v1/inquiries
     * 사원이 담당자 1명을 선택해 문의를 등록합니다.
     */
    @PostMapping("/api/v1/inquiries")
    @ResponseStatus(HttpStatus.CREATED)
    public InquiryResponse create(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @RequestParam(name = "assigneeId", required = false) Long assigneeId,
            @RequestParam(name = "title", required = false) String title,
            @RequestParam(name = "content", required = false) String content,
            @RequestParam(name = "priority", required = false) String priority,
            @RequestParam(name = "attachments", required = false) List<MultipartFile> attachments
    ) {
        // TODO(개선): assigneeId를 Long으로 바인딩해 숫자가 아니면 INVALID_REQUEST가 나간다.
        //  계약 일관성을 위해 String으로 받아 DTO에서 파싱하고 INVALID_INQUIRY로 통일하는 편이 낫다.
        // TODO(개선): 201 응답에 Location 헤더(/api/v1/inquiries/{id})를 추가한다. (REST 컨벤션 5.1)
        InquiryCreateRequest request = InquiryCreateRequest.of(
                assigneeId,
                title,
                content,
                priority,
                attachments == null ? List.of() : attachments
        );
        return inquiryService.create(loginMember, request);
    }

    /**
     * GET /api/v1/inquiries
     * 사원은 본인 문의, 관리자는 본인이 담당자인 문의 목록을 조회합니다.
     */
    @GetMapping("/api/v1/inquiries")
    public InquiryListResponse inquiries(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "priority", required = false) String priority,
            @RequestParam(name = "memberId", required = false) String memberId,
            @RequestParam(name = "createdFrom", required = false) String createdFrom,
            @RequestParam(name = "createdTo", required = false) String createdTo,
            @RequestParam(name = "sort", required = false) String sort
    ) {
        return inquiryService.findInquiries(
                loginMember, page, size, status, priority, memberId, createdFrom, createdTo, sort);
    }

    /**
     * GET /api/v1/inquiries/{inquiryId}
     * 문의 내용, 첨부 이미지 정보와 답변을 조회합니다.
     */
    @GetMapping("/api/v1/inquiries/{inquiryId}")
    public InquiryResponse inquiry(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable long inquiryId
    ) {
        return inquiryService.getInquiry(loginMember, inquiryId);
    }

    /**
     * DELETE /api/v1/inquiries/{inquiryId}
     * 작성자 또는 지정 담당자가 문의와 첨부·답변을 하드 삭제합니다.
     */
    @DeleteMapping("/api/v1/inquiries/{inquiryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable long inquiryId
    ) {
        inquiryService.deleteInquiry(loginMember, inquiryId);
    }

    /**
     * GET /api/v1/inquiries/{inquiryId}/attachments/{attachmentId}
     * 권한 있는 사용자가 문의 첨부 이미지를 다운로드합니다.
     */
    @GetMapping("/api/v1/inquiries/{inquiryId}/attachments/{attachmentId}")
    public ResponseEntity<org.springframework.core.io.Resource> downloadAttachment(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable long inquiryId,
            @PathVariable String attachmentId
    ) {
        InquiryAttachmentDownload download = inquiryService.downloadAttachment(loginMember, inquiryId, attachmentId);
        MediaType mediaType = download.mimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(download.mimeType());
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(download.fileName() == null ? "attachment" : download.fileName())
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.resource());
    }

    /**
     * PUT /api/v1/inquiries/{inquiryId}/answer
     * 지정 담당자가 답변을 작성하거나 전체 교체합니다.
     */
    @PutMapping("/api/v1/inquiries/{inquiryId}/answer")
    public InquiryAnswerResponse upsertAnswer(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable long inquiryId,
            @Valid @RequestBody InquiryAnswerRequest request
    ) {
        return inquiryService.upsertAnswer(loginMember, inquiryId, request.content());
    }

    /**
     * DELETE /api/v1/inquiries/{inquiryId}/answer
     * 지정 담당자가 답변을 하드 삭제하고 문의를 답변 대기 상태로 되돌립니다.
     */
    @DeleteMapping("/api/v1/inquiries/{inquiryId}/answer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAnswer(
            @AuthenticationPrincipal AuthenticatedMember loginMember,
            @PathVariable long inquiryId
    ) {
        inquiryService.deleteAnswer(loginMember, inquiryId);
    }
}
