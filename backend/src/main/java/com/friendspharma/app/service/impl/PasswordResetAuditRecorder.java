package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.AclPasswordResetAudit;
import com.friendspharma.app.repository.AclPasswordResetAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * [V71] Writes a password-reset audit row in its own transaction.
 *
 * <p><b>Why this is a separate bean.</b> A refusal is recorded and then thrown,
 * and the throw rolls back the caller's transaction — taking the audit row with
 * it. Written inline, the table would end up holding successes only, which is
 * the opposite of what it is for: a Super Admin account probing repeatedly at
 * accounts it may not touch leaves no trace at all, because every one of those
 * attempts rolls itself back.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction and commits this row
 * on its own, so it survives whatever happens next. It has to live in another
 * bean because the proxy that applies the annotation is bypassed by a plain
 * {@code this.} call within one class — the propagation would silently do
 * nothing, which is the failure mode that looks exactly like it working.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetAuditRecorder {

    private final AclPasswordResetAuditRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long pidUser, String userName, String fullName, Long pidBranch,
                       String branchName, Long pidOrg, Long actorId, String actorName,
                       String ip, String outcome, String reason) {
        try {
            repo.save(AclPasswordResetAudit.builder()
                    .pidUser(pidUser)
                    .userName(userName == null ? "(unknown)" : userName)
                    .fullName(fullName)
                    .pidBranch(pidBranch).branchName(branchName).pidOrg(pidOrg)
                    .resetByUser(actorId)
                    .resetByName(actorName == null ? "(unknown)" : actorName)
                    .resetAt(LocalDateTime.now()).ipAddress(ip)
                    .outcome(outcome).reason(reason)
                    .build());
        } catch (RuntimeException e) {
            /* Logged loudly rather than rethrown. An audit write that fails is
               itself worth knowing about, but failing the reset because of it
               would leave a password already changed with no record at all —
               strictly worse than a changed password and a log line. */
            log.error("Could not write the password-reset audit row for user {} by {} ({}): {}",
                    pidUser, actorName, outcome, e.getMessage(), e);
        }
    }
}
