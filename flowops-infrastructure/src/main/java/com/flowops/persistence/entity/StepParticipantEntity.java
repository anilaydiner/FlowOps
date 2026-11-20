package com.flowops.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Read-only mapping used by the approval-queue EXISTS predicates. */
@Entity(name = "StepParticipant")
@Table(name = "step_participants")
@Immutable
public class StepParticipantEntity {

    @Id
    private UUID id;

    @Column(name = "slot_id", nullable = false)
    private UUID slotId;

    @Column(name = "step_instance_id", nullable = false)
    private Long stepInstanceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "predecessor_id")
    private UUID predecessorId;

    protected StepParticipantEntity() {
    }

}
