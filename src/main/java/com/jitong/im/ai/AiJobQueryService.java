package com.jitong.im.ai;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
class AiJobQueryService {

    private final JdbcClient jdbc;

    AiJobQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<AiJobStatusResponse> listActiveForOwner(UUID ownerUserId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, kind, status, error_code,
                               created_at, expires_at
                        FROM ai_jobs
                        WHERE owner_user_id = :ownerUserId
                          AND expires_at > CURRENT_TIMESTAMP
                        ORDER BY created_at DESC, id
                        """)
                .param("ownerUserId", ownerUserId)
                .query((row, rowNumber) -> new AiJobStatusResponse(
                        1,
                        row.getObject("id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getString("kind"),
                        row.getString("status"),
                        row.getString("error_code"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
