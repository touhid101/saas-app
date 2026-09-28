package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.AccSetting;
import com.friendspharma.app.repository.AccSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accounting policy, per organisation, as data.
 *
 * <p>A missing row means the default below, so an organisation works before
 * anybody has visited the settings screen. Every value is a string in the
 * table and typed here, in one place.
 */
@Service
@RequiredArgsConstructor
public class AccSettings {

    /** Month the fiscal year starts in (1 = January, 7 = July). */
    public static final String FISCAL_YEAR_START_MONTH = "fiscal_year_start_month";
    /** Reporting currency. */
    public static final String CURRENCY                = "currency";
    /** Absolute difference at closing that needs no reason. */
    public static final String CASH_TOLERANCE          = "cash_tolerance";
    /** Y: a short / over at closing is posted to Cash Short/Over so the ledger equals the count. */
    public static final String POST_CASH_VARIANCE      = "post_cash_variance";
    /** Y: closing goes to SUBMITTED and needs Approve to become CLOSED. */
    public static final String CLOSING_REQUIRES_REVIEW = "closing_requires_review";
    /** Y: a cash posting on a date whose day sheet is not OPEN is refused. */
    public static final String REQUIRE_DAY_OPEN        = "require_day_open";
    /** REFUSE: a cash posting into a CLOSED day is refused. NEXT_DAY: it is dated the next day that is not closed. */
    public static final String LATE_POSTING_POLICY     = "late_posting_policy";
    /** ACCRUAL: expense claims hit expense at approval; CASH: at payment. */
    public static final String EXPENSE_CLAIM_BASIS     = "expense_claim_basis";
    /** Y: journals from business modules post to the ledger. N: events are recorded SKIPPED (books not started). */
    public static final String POSTING_ENABLED         = "posting_enabled";
    /** Y: a manual journal posts on save; N: it waits for Approve. */
    public static final String MANUAL_JOURNAL_AUTO_POST = "manual_journal_auto_post";
    /** Y: the delivery-man collection register posts through Cash in Transit; N: Collect posts the till directly. */
    public static final String USE_CASH_IN_TRANSIT     = "use_cash_in_transit";

    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>() {{
        put(FISCAL_YEAR_START_MONTH, "1");
        put(CURRENCY, "BDT");
        put(CASH_TOLERANCE, "0");
        put(POST_CASH_VARIANCE, "N");
        put(CLOSING_REQUIRES_REVIEW, "N");
        put(REQUIRE_DAY_OPEN, "N");
        put(LATE_POSTING_POLICY, "REFUSE");
        put(EXPENSE_CLAIM_BASIS, "ACCRUAL");
        put(POSTING_ENABLED, "Y");
        put(MANUAL_JOURNAL_AUTO_POST, "N");
        put(USE_CASH_IN_TRANSIT, "Y");
    }};

    private final AccSettingRepository repo;

    @Transactional(readOnly = true)
    public String get(Long orgId, String key) {
        return repo.findByPidOrgAndSettingKey(orgId, key).map(AccSetting::getSettingValue)
                .filter(v -> v != null && !v.isBlank()).orElse(DEFAULTS.get(key));
    }

    public boolean is(Long orgId, String key) { return "Y".equalsIgnoreCase(get(orgId, key)); }

    public int integer(Long orgId, String key) {
        try { return Integer.parseInt(get(orgId, key)); } catch (Exception e) { return Integer.parseInt(DEFAULTS.get(key)); }
    }

    public BigDecimal decimal(Long orgId, String key) {
        try { return new BigDecimal(get(orgId, key)); } catch (Exception e) { return new BigDecimal(DEFAULTS.get(key)); }
    }

    @Transactional(readOnly = true)
    public Map<String, String> all(Long orgId) {
        Map<String, String> out = new LinkedHashMap<>(DEFAULTS);
        repo.findByPidOrg(orgId).forEach(s -> { if (s.getSettingValue() != null) out.put(s.getSettingKey(), s.getSettingValue()); });
        return out;
    }

    @Transactional
    public void set(Long orgId, String key, String value) {
        if (!DEFAULTS.containsKey(key)) throw new IllegalArgumentException("'" + key + "' is not an accounting setting.");
        AccSetting s = repo.findByPidOrgAndSettingKey(orgId, key)
                .orElseGet(() -> AccSetting.builder().pidOrg(orgId).settingKey(key).build());
        s.setSettingValue(value == null ? null : value.trim());
        repo.save(s);
    }
}
