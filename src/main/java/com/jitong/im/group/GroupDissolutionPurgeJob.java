package com.jitong.im.group;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
class GroupDissolutionPurgeJob {

    private final GroupRepository repository;
    private final Clock clock;

    @Autowired
    GroupDissolutionPurgeJob(GroupRepository repository) {
        this(repository, Clock.systemUTC());
    }

    GroupDissolutionPurgeJob(GroupRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${jitong.group.dissolution-purge-interval:3600000}",
            initialDelayString = "${jitong.group.dissolution-purge-initial-delay:60000}")
    @Transactional
    void purgeDueGroups() {
        for (GroupRepository.DissolvedGroupRecord group : repository.findGroupsDueForPurge(clock.instant())) {
            repository.purgeGroupContent(group.conversationId());
        }
    }
}
