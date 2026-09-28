package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.AccAccount;
import com.friendspharma.app.entity.AccBankAccount;
import com.friendspharma.app.entity.AccPaymentMode;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.AccAccountRepository;
import com.friendspharma.app.repository.AccBankAccountRepository;
import com.friendspharma.app.repository.AccPaymentModeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The chart of accounts: one per organisation, provisioned from the template,
 * addressed by the rules through posting roles.
 *
 * <p>A role ({@code CASH_REGISTER}, {@code AR}, {@code SALES_REVENUE} ...) is
 * unique within an organisation. A rule names the role; this service finds the
 * account. Renaming an account changes nothing for the rules; deleting a
 * system account is refused, because a rule would then have nowhere to post.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccChartService {

    public static final List<String> TYPES = List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE");

    private final AccAccountRepository     accountRepo;
    private final AccBankAccountRepository bankRepo;
    private final AccPaymentModeRepository modeRepo;
    @PersistenceContext private EntityManager em;

    /**
     * Every account of an organisation, by id — read once per organisation,
     * evicted on any change to the chart and whenever the transaction that
     * filled it rolls back: {@link #ensureProvisioned} creates the chart inside
     * the first posting's transaction, and a cache filled from those rows must
     * not outlive a rollback (see {@link UncommittedGuard}).
     */
    private final java.util.concurrent.ConcurrentMap<Long, Map<Long, AccAccount>> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Long, List<AccPaymentMode>> modeCache = new java.util.concurrent.ConcurrentHashMap<>();

    private Map<Long, AccAccount> cached(Long orgId) {
        return cache.computeIfAbsent(orgId, o -> {
            UncommittedGuard.evictUnlessCommitted(() -> cache.remove(o));
            Map<Long, AccAccount> m = new LinkedHashMap<>();
            for (AccAccount a : accountRepo.findByPidOrgOrderBySortOrderAscAccountCodeAsc(o)) m.put(a.getPidAccount(), a);
            return m;
        });
    }

    public void evict(Long orgId) { cache.remove(orgId); modeCache.remove(orgId); }

    /** The role an account carries, without a query. */
    public String roleOf(Long orgId, Long pidAccount) {
        AccAccount a = cached(orgId).get(pidAccount);
        return a == null ? null : a.getRoleKey();
    }

    /** An account by id from the cache, or empty when it is not in this organisation. */
    public Optional<AccAccount> cachedById(Long orgId, Long pidAccount) {
        return Optional.ofNullable(cached(orgId).get(pidAccount));
    }

    /* ── Provisioning ───────────────────────────────────────────────────── */

    /**
     * Copies the template into an organisation that has no accounts yet.
     * Idempotent: an organisation already provisioned is left as it is.
     */
    @Transactional
    public void ensureProvisioned(Long orgId) {
        if (accountRepo.countByPidOrg(orgId) > 0) return;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT account_code, account_name, account_type, parent_code, is_group, normal_side, role_key, allow_manual, sort_order "
              + "FROM pharma.acc_account_template ORDER BY sort_order").getResultList();
        Map<String, AccAccount> byCode = new LinkedHashMap<>();
        for (Object[] r : rows) {
            AccAccount a = AccAccount.builder()
                    .pidOrg(orgId).accountCode((String) r[0]).accountName((String) r[1]).accountType((String) r[2])
                    .isGroup(((Number) r[4]).intValue()).normalSide(String.valueOf(r[5])).roleKey((String) r[6])   // normal_side is VARCHAR(1): the driver hands back a Character
                    .allowManual(((Number) r[7]).intValue()).isSystem(1).isActive(1).sortOrder(((Number) r[8]).intValue())
                    .build();
            byCode.put((String) r[0], accountRepo.save(a));
        }
        for (Object[] r : rows) {
            String parent = (String) r[3];
            if (parent != null && byCode.containsKey(parent)) {
                AccAccount a = byCode.get((String) r[0]);
                a.setPidParent(byCode.get(parent).getPidAccount());
                accountRepo.save(a);
            }
        }
        evict(orgId);
        log.info("Chart of accounts provisioned for organisation {} ({} accounts)", orgId, byCode.size());
    }

    /* ── Resolution ─────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public AccAccount byRole(Long orgId, String roleKey) {
        return byRoleIfAny(orgId, roleKey).orElseThrow(() -> new BusinessException(
                "No account carries the posting role '" + roleKey + "' in this organisation's chart of accounts. "
                + "Assign the role to an account on Chart of Accounts before this transaction can be booked.",
                HttpStatus.CONFLICT, "ACC_ROLE_UNMAPPED"));
    }

    @Transactional(readOnly = true)
    public Optional<AccAccount> byRoleIfAny(Long orgId, String roleKey) {
        if (roleKey == null) return Optional.empty();
        for (AccAccount a : cached(orgId).values()) if (roleKey.equals(a.getRoleKey())) return Optional.of(a);
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public AccAccount owned(Long orgId, Long pidAccount) {
        return accountRepo.findByPidAccountAndPidOrg(pidAccount, orgId)
                .orElseThrow(() -> new BusinessException("That account is not in this organisation's chart of accounts.",
                        HttpStatus.NOT_FOUND, "ACC_ACCOUNT_NOT_FOUND"));
    }

    /**
     * The cash account a payment mode lands on, or empty for a mode that moves
     * no cash (CREDIT). Free-text modes are normalised: "Bank Transfer",
     * "bank_transfer" and "BANKTRANSFER" are one key.
     */
    @Transactional(readOnly = true)
    public Optional<AccAccount> byPaymentMode(Long orgId, String mode) {
        String key = normaliseMode(mode);
        AccPaymentMode hit = null;
        for (AccPaymentMode m : modeCache.computeIfAbsent(orgId, o -> { UncommittedGuard.evictUnlessCommitted(() -> modeCache.remove(o)); return new ArrayList<>(modeRepo.findEffective(o)); })) {
            if (m.getModeKey().equals(key)) { hit = m; break; }
        }
        if (hit == null) {
            // Unknown modes are treated as the till rather than refused: a sale
            // rung up with a mode nobody configured is still cash in the shop,
            // and the reconciliation screen lists unknown modes for mapping.
            log.warn("Payment mode '{}' has no mapping in organisation {}; treating it as cash", mode, orgId);
            return byRoleIfAny(orgId, "CASH_REGISTER");
        }
        if (hit.getAccountRole() == null) return Optional.empty();
        if (hit.getPidBankAccount() != null) {
            return bankRepo.findById(hit.getPidBankAccount()).map(b -> accountRepo.findById(b.getPidAccount()).orElse(null))
                    .filter(Objects::nonNull);
        }
        if ("BANK_DEFAULT".equals(hit.getAccountRole())) {
            Optional<AccBankAccount> def = bankRepo.findFirstByPidOrgAndIsDefault(orgId, 1);
            if (def.isPresent()) return accountRepo.findById(def.get().getPidAccount());
        }
        return byRoleIfAny(orgId, hit.getAccountRole());
    }

    public static String normaliseMode(String mode) {
        if (mode == null || mode.isBlank()) return "CASH";
        return mode.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    }

    /* ── Chart maintenance ──────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<AccAccount> list(Long orgId) {
        ensureProvisionedReadOnlyWarning(orgId);
        return accountRepo.findByPidOrgOrderBySortOrderAscAccountCodeAsc(orgId);
    }

    private void ensureProvisionedReadOnlyWarning(Long orgId) {
        if (accountRepo.countByPidOrg(orgId) == 0) log.warn("Organisation {} has no chart of accounts yet", orgId);
    }

    @Transactional
    public AccAccount create(Long orgId, AccAccount req) {
        ensureProvisioned(orgId);
        if (req.getAccountCode() == null || req.getAccountCode().isBlank()) throw bad("An account needs a code.");
        if (req.getAccountName() == null || req.getAccountName().isBlank()) throw bad("An account needs a name.");
        if (!TYPES.contains(req.getAccountType())) throw bad("Account type must be one of " + TYPES + ".");
        accountRepo.findByPidOrgAndAccountCode(orgId, req.getAccountCode().trim())
                .ifPresent(x -> { throw bad("Account code " + x.getAccountCode() + " is already used by " + x.getAccountName() + "."); });
        if (req.getRoleKey() != null && !req.getRoleKey().isBlank()) {
            accountRepo.findByPidOrgAndRoleKey(orgId, req.getRoleKey().trim())
                    .ifPresent(x -> { throw bad("The posting role " + x.getRoleKey() + " already belongs to " + x.getAccountName() + "."); });
        }
        AccAccount parent = req.getPidParent() == null ? null : owned(orgId, req.getPidParent());
        if (parent != null && !parent.getAccountType().equals(req.getAccountType())) throw bad("A child account must share its parent's type.");
        AccAccount a = AccAccount.builder()
                .pidOrg(orgId).accountCode(req.getAccountCode().trim()).accountName(req.getAccountName().trim())
                .accountType(req.getAccountType()).pidParent(parent == null ? null : parent.getPidAccount())
                .isGroup(req.getIsGroup() == null ? 0 : req.getIsGroup())
                .normalSide(req.getNormalSide() != null ? req.getNormalSide()
                        : (Set.of("ASSET", "EXPENSE").contains(req.getAccountType()) ? "D" : "C"))
                .roleKey(blankToNull(req.getRoleKey())).allowManual(req.getAllowManual() == null ? 1 : req.getAllowManual())
                .isSystem(0).isActive(1).description(req.getDescription())
                .sortOrder(req.getSortOrder() == null ? 500 : req.getSortOrder()).build();
        evict(orgId);
        return accountRepo.save(a);
    }

    @Transactional
    public AccAccount update(Long orgId, Long id, AccAccount req) {
        AccAccount a = owned(orgId, id);
        if (req.getAccountName() != null && !req.getAccountName().isBlank()) a.setAccountName(req.getAccountName().trim());
        if (req.getDescription() != null) a.setDescription(req.getDescription());
        if (req.getSortOrder() != null) a.setSortOrder(req.getSortOrder());
        if (req.getAllowManual() != null) a.setAllowManual(req.getAllowManual());
        if (req.getIsActive() != null) {
            if (req.getIsActive() == 0 && a.getIsSystem() == 1 && a.getRoleKey() != null)
                throw bad("This account carries the posting role " + a.getRoleKey() + " and cannot be retired; move the role first.");
            a.setIsActive(req.getIsActive());
        }
        if (req.getRoleKey() != null) {
            String role = blankToNull(req.getRoleKey());
            if (role != null && !role.equals(a.getRoleKey())) {
                Optional<AccAccount> other = accountRepo.findByPidOrgAndRoleKey(orgId, role);
                if (other.isPresent()) {
                    // Moving a role: the previous holder gives it up.
                    AccAccount o = other.get(); o.setRoleKey(null); accountRepo.save(o);
                }
            }
            a.setRoleKey(role);
        }
        if (req.getPidParent() != null && !req.getPidParent().equals(a.getPidParent())) {
            AccAccount parent = owned(orgId, req.getPidParent());
            if (parent.getPidAccount().equals(a.getPidAccount())) throw bad("An account cannot be its own parent.");
            if (!parent.getAccountType().equals(a.getAccountType())) throw bad("A child account must share its parent's type.");
            a.setPidParent(parent.getPidAccount());
        }
        evict(orgId);
        return accountRepo.save(a);
    }

    /* ── Bank accounts ──────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<AccBankAccount> banks(Long orgId) { return bankRepo.findByPidOrgOrderByIsDefaultDescBankNameAsc(orgId); }

    @Transactional
    public AccBankAccount createBank(Long orgId, Long branchId, AccBankAccount req) {
        ensureProvisioned(orgId);
        if (req.getBankName() == null || req.getBankName().isBlank()) throw bad("A bank account needs the bank's name.");
        AccAccount group = accountRepo.findByPidOrgAndAccountCode(orgId, "1130").orElseGet(() -> byRole(orgId, "BANK_DEFAULT"));
        List<AccBankAccount> existing = banks(orgId);
        String code = "1131";
        for (int i = 1; i < 100; i++) {
            String candidate = "11" + (30 + i);
            if (accountRepo.findByPidOrgAndAccountCode(orgId, candidate).isEmpty()) { code = candidate; break; }
        }
        boolean first = existing.isEmpty();
        AccAccount ledger;
        if (first && accountRepo.findByPidOrgAndRoleKey(orgId, "BANK_DEFAULT").isPresent()) {
            // The template's Bank - Main Account becomes the first real bank account.
            ledger = byRole(orgId, "BANK_DEFAULT");
            ledger.setAccountName("Bank - " + req.getBankName().trim() + (req.getAccountNo() == null ? "" : " " + req.getAccountNo()));
            accountRepo.save(ledger);
        } else {
            ledger = accountRepo.save(AccAccount.builder().pidOrg(orgId).accountCode(code)
                    .accountName("Bank - " + req.getBankName().trim() + (req.getAccountNo() == null ? "" : " " + req.getAccountNo()))
                    .accountType("ASSET").pidParent(group.getPidAccount()).isGroup(0).normalSide("D").allowManual(1)
                    .isSystem(0).isActive(1).sortOrder(1130 + existing.size() + 1).build());
        }
        AccBankAccount b = AccBankAccount.builder().pidOrg(orgId).pidBranch(req.getPidBranch() != null ? req.getPidBranch() : branchId)
                .pidAccount(ledger.getPidAccount()).bankName(req.getBankName().trim()).accountTitle(req.getAccountTitle())
                .accountNo(req.getAccountNo()).branchName(req.getBranchName())
                .currency(req.getCurrency() == null ? "BDT" : req.getCurrency())
                .isDefault(first || Integer.valueOf(1).equals(req.getIsDefault()) ? 1 : 0).isActive(1).build();
        if (b.getIsDefault() == 1) existing.forEach(x -> { x.setIsDefault(0); bankRepo.save(x); });
        evict(orgId);
        return bankRepo.save(b);
    }

    @Transactional
    public AccBankAccount updateBank(Long orgId, Long id, AccBankAccount req) {
        AccBankAccount b = bankRepo.findById(id).filter(x -> x.getPidOrg().equals(orgId))
                .orElseThrow(() -> bad("That bank account is not in this organisation."));
        if (req.getBankName() != null) b.setBankName(req.getBankName());
        if (req.getAccountTitle() != null) b.setAccountTitle(req.getAccountTitle());
        if (req.getAccountNo() != null) b.setAccountNo(req.getAccountNo());
        if (req.getBranchName() != null) b.setBranchName(req.getBranchName());
        if (req.getIsActive() != null) b.setIsActive(req.getIsActive());
        if (Integer.valueOf(1).equals(req.getIsDefault())) {
            banks(orgId).forEach(x -> { x.setIsDefault(0); bankRepo.save(x); });
            b.setIsDefault(1);
        }
        return bankRepo.save(b);
    }

    @Transactional(readOnly = true)
    public List<AccPaymentMode> paymentModes(Long orgId) { return modeRepo.findEffective(orgId); }

    @Transactional
    public AccPaymentMode savePaymentMode(Long orgId, AccPaymentMode req) {
        String key = normaliseMode(req.getModeKey());
        AccPaymentMode m = modeRepo.findEffective(orgId).stream().filter(x -> orgId.equals(x.getPidOrg()) && x.getModeKey().equals(key))
                .findFirst().orElseGet(() -> AccPaymentMode.builder().pidOrg(orgId).modeKey(key).isActive(1).build());
        m.setModeLabel(req.getModeLabel() == null ? key : req.getModeLabel());
        m.setAccountRole(blankToNull(req.getAccountRole()));
        m.setPidBankAccount(req.getPidBankAccount());
        if (req.getIsActive() != null) m.setIsActive(req.getIsActive());
        evict(orgId);
        return modeRepo.save(m);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static BusinessException bad(String m) { return new BusinessException(m, HttpStatus.UNPROCESSABLE_ENTITY, "ACC_VALIDATION"); }
}
