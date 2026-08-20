package com.jitong.im.media;

import com.jitong.im.auth.AuthService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/media")
class MediaController {

    private final AuthService authService;
    private final MediaService mediaService;

    MediaController(AuthService authService, MediaService mediaService) {
        this.authService = authService;
        this.mediaService = mediaService;
    }

    @PostMapping(
            value = "/images",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    MediaUploadResponse uploadImage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam UUID uploadId,
            @RequestPart("file") MultipartFile file
    ) {
        return mediaService.uploadMessageImage(
                authService.requireUserId(authorization),
                uploadId,
                file);
    }

    @GetMapping("/{mediaId}")
    ResponseEntity<InputStreamResource> download(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID mediaId,
            @RequestParam(defaultValue = "full") String variant
    ) {
        MediaDownload download = mediaService.download(
                authService.requireUserId(authorization),
                mediaId,
                variant);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).mustRevalidate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.contentLength())
                .body(new InputStreamResource(download.content()));
    }
}
