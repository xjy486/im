package com.jitong.im.group;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupDissolutionPurgeJobTest {

    @Test
    void purges_groups_once_the_thirty_day_safety_buffer_has_elapsed() {
        GroupRepository repository = mock(GroupRepository.class);
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        UUID conversationId = UUID.randomUUID();
        when(repository.findGroupsDueForPurge(now)).thenReturn(List.of(
                new GroupRepository.DissolvedGroupRecord(
                        conversationId,
                        "12345678903",
                        UUID.randomUUID(),
                        now.minusSeconds(1),
                        now.minusSeconds(1))));

        GroupDissolutionPurgeJob job = new GroupDissolutionPurgeJob(
                repository,
                Clock.fixed(now, ZoneOffset.UTC));

        job.purgeDueGroups();

        verify(repository).purgeGroupContent(conversationId);
    }
}
