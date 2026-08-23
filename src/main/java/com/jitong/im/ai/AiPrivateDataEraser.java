package com.jitong.im.ai;

import com.jitong.im.auth.PrivateAiDataEraser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class AiPrivateDataEraser implements PrivateAiDataEraser {

    private final AiRepository repository;

    AiPrivateDataEraser(AiRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void eraseForRetirement(UUID userId) {
        repository.eraseForRetirement(userId);
    }
}
