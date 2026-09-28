package com.friendspharma.app.entity.base;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Base entity — audit fields matching the Oracle schema.
 * Provides both legacy names (insertedDate/updatedDate)
 * and new-service aliases (createdAt/updatedAt).
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Column(name = "status", length = 1)
    private String status = "Y";

    @CreatedDate
    @Column(name = "inserted_date", updatable = false)
    private LocalDateTime insertedDate;

    @CreatedBy
    @Column(name = "inserted_by", length = 50, updatable = false)
    private String insertedBy;

    @LastModifiedDate
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    @LastModifiedBy
    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    /** Alias for insertedDate — used by new service/DTO layer */
    public LocalDateTime getCreatedAt()          { return insertedDate; }

    /** Alias for updatedDate — used by new service/DTO layer */
    public LocalDateTime getUpdatedAt()           { return updatedDate; }
}
