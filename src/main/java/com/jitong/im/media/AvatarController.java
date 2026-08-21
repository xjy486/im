package com.jitong.im.media;

import com.jitong.im.auth.AuthService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1")
class AvatarController {

    private final AuthService authService;
    private final AvatarService avatarService;

    AvatarController(
            AuthService authService,
            AvatarService avatarService
    ) {
        this.authService = authService;
        this.avatarService = avatarService;
    }

    @PutMapping(
            value = "/users/me/avatar",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    AvatarUploadResponse replace(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam UUID uploadId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Integer cropX,
            @RequestParam(required = false) Integer cropY,
            @RequestParam(required = false) Integer cropWidth,
            @RequestParam(required = false) Integer cropHeight
    ) {
        return avatarService.replaceUserAvatar(
                authService.requireUserId(authorization),
                uploadId,
                file,
                crop(cropX, cropY, cropWidth, cropHeight));
    }

    @DeleteMapping("/users/me/avatar")
    ResponseEntity<Void> remove(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        avatarService.removeUserAvatar(authService.requireUserId(authorization));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}/avatar")
    ResponseEntity<InputStreamResource> download(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "full") String variant,
            @RequestParam(required = false) Long avatarVersion
    ) {
        MediaDownload download = avatarService.downloadUserAvatar(
                authService.requireUserId(authorization),
                userId,
                variant,
                avatarVersion);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).mustRevalidate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.contentLength())
                .body(new InputStreamResource(download.content()));
    }

    @GetMapping("/users/{userId}/profile")
    AvatarService.UserProfile profile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID userId
    ) {
        return avatarService.visibleProfile(
                authService.requireUserId(authorization),
                userId);
    }

    private AvatarCrop crop(
            Integer x,
            Integer y,
            Integer width,
            Integer height
    ) {
        if (x == null && y == null && width == null && height == null) {
            return null;
        }
        if (x == null || y == null || width == null || height == null) {
            throw new com.jitong.im.media.MediaException(
                    com.jitong.im.platform.error.ApiErrorDefinition.MEDIA_INVALID);
        }
        return new AvatarCrop(x, y, width, height);
    }
}
