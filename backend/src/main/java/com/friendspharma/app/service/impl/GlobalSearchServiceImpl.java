package com.friendspharma.app.service.impl;

import com.friendspharma.app.security.SecurityUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The topbar search box.
 *
 * <p><b>What this replaces.</b> Two broken halves that never met.
 *
 * <p>The screen searched {@code mockData.js} in the browser — the PRODUCTS,
 * CUSTOMERS, SUPPLIERS, SALES, PURCHASES and REQUISITIONS seed arrays — and
 * never called the server at all. Typing a real customer's name found nothing;
 * typing a seed name found a record that does not exist in this database.
 *
 * <p>The server half could not have worked either. Its SQL named
 * {@code POS_TRANSACTION_MST.PID_TRANSACTION}, {@code TRAN_TYPE='SALE'} and
 * {@code PID_CUSTOMER} — the columns are {@code PID_TRAN_MST},
 * {@code TRANSACTION_TYPE='SL'} and {@code PID_SUP_CUST} — and
 * {@code POS_SUPPLIER_INFO.SUPPLIER_CODE}, which does not exist. It also filtered
 * on {@code IS_ACTIVE}, where this schema uses {@code STATUS='Y'}. Every query
 * sat inside a {@code catch (Exception)} that returned an empty list, so the box
 * would have reported "no results" instead of failing, for ever.
 *
 * <p><b>Branch-scoped.</b> A branch is a pharmacy. The old query filtered on the
 * organisation, so in an organisation running three pharmacies the box would
 * have offered another branch's customers and invoices. Every query here carries
 * the branch, resolved from the session rather than the request.
 *
 * <p><b>Two gates.</b> The box itself is MOD29, checked in the controller. Each
 * kind of record is then gated by the View right on the module that owns it, so
 * search is a faster way to reach records the reader could already open, never a
 * way to reach new ones. A Pharmacist without Purchase sees no purchase hits.
 *
 * <p><b>Parameterised.</b> The organisation id used to be concatenated into the
 * SQL string. It came from the session so nothing could be injected through it,
 * but the next person to add a filter would have followed the pattern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GlobalSearchServiceImpl {

    @PersistenceContext
    private EntityManager em;

    private final PermissionGuard permissions;
    private final BranchScopeService branchScope;

    /** Enough hits per kind to recognise the one you meant, few enough to scan. */
    private static final int MAX_PER_TYPE = 5;

    /** Below two characters every query matches almost everything. */
    private static final int MIN_QUERY_LENGTH = 2;

    /* The module that owns each kind of record. Its View right is the gate. */
    private static final String MOD_PRODUCT     = "MOD17";
    private static final String MOD_PARTIES     = "MOD02";
    private static final String MOD_SALES       = "MOD04";
    private static final String MOD_PURCHASE    = "MOD03";
    private static final String MOD_REQUISITION = "MOD11";

    public List<Map<String, Object>> search(String query) {
        if (query == null || query.trim().length() < MIN_QUERY_LENGTH) return List.of();

        Long branchId = branchScope.resolve(null);
        Long orgId = SecurityUtils.getCurrentOrgId();
        String like = "%" + query.trim().toUpperCase(Locale.ROOT) + "%";

        List<Map<String, Object>> out = new ArrayList<>();

        if (permissions.has(MOD_PRODUCT, PermissionGuard.Right.VIEW)) {
            out.addAll(run("""
                SELECT 'Product', p.PID_PRODUCT, p.PRODUCT_NAME,
                       COALESCE(p.PRODUCT_CODE, p.BARCODE), 'productmaster'
                  FROM PHARMA.POS_PRODUCT_INFO p
                 WHERE p.PID_ORG = :orgId AND p.PID_BRANCH = :branchId AND p.STATUS = 'Y'
                   AND (UPPER(p.PRODUCT_NAME) LIKE :q
                        OR UPPER(p.PRODUCT_CODE) LIKE :q
                        OR UPPER(p.BARCODE) LIKE :q)
                 ORDER BY p.PRODUCT_NAME
                """, orgId, branchId, like));
        }

        if (permissions.has(MOD_PARTIES, PermissionGuard.Right.VIEW)) {
            out.addAll(run("""
                SELECT 'Customer', c.PID_CUSTOMER, c.CUSTOMER_NAME,
                       COALESCE(c.MOBILE_NO, c.CUSTOMER_CODE), 'master'
                  FROM PHARMA.POS_CUSTOMER_INFO c
                 WHERE c.PID_ORG = :orgId AND c.PID_BRANCH = :branchId AND c.STATUS = 'Y'
                   AND (UPPER(c.CUSTOMER_NAME) LIKE :q
                        OR UPPER(c.CUSTOMER_CODE) LIKE :q
                        OR c.MOBILE_NO LIKE :q)
                 ORDER BY c.CUSTOMER_NAME
                """, orgId, branchId, like));

            // No SUPPLIER_CODE column exists, whatever the old query believed.
            out.addAll(run("""
                SELECT 'Supplier', s.PID_SUPPLIER, s.SUPPLIER_NAME, s.MOBILE_NO, 'master'
                  FROM PHARMA.POS_SUPPLIER_INFO s
                 WHERE s.PID_ORG = :orgId AND s.PID_BRANCH = :branchId AND s.STATUS = 'Y'
                   AND (UPPER(s.SUPPLIER_NAME) LIKE :q OR s.MOBILE_NO LIKE :q)
                 ORDER BY s.SUPPLIER_NAME
                """, orgId, branchId, like));
        }

        if (permissions.has(MOD_SALES, PermissionGuard.Right.VIEW)) {
            // Newest first: an invoice number typed from memory is nearly always
            // a recent one, and a sales register runs to tens of thousands.
            out.addAll(run("""
                SELECT 'Sale', t.PID_TRAN_MST, t.INVOICE_NO,
                       COALESCE(c.CUSTOMER_NAME, t.CHALLAN_NO), 'sales'
                  FROM PHARMA.POS_TRANSACTION_MST t
                  LEFT JOIN PHARMA.POS_CUSTOMER_INFO c ON c.PID_CUSTOMER = t.PID_SUP_CUST
                 WHERE t.PID_ORG = :orgId AND t.PID_BRANCH = :branchId
                   AND t.TRANSACTION_TYPE = 'SL'
                   AND (UPPER(t.INVOICE_NO) LIKE :q
                        OR UPPER(t.CHALLAN_NO) LIKE :q
                        OR UPPER(c.CUSTOMER_NAME) LIKE :q)
                 ORDER BY t.TRANSACTION_DATE DESC, t.PID_TRAN_MST DESC
                """, orgId, branchId, like));
        }

        if (permissions.has(MOD_PURCHASE, PermissionGuard.Right.VIEW)) {
            out.addAll(run("""
                SELECT 'Purchase', t.PID_TRAN_MST, t.INVOICE_NO, s.SUPPLIER_NAME, 'purchase'
                  FROM PHARMA.POS_TRANSACTION_MST t
                  LEFT JOIN PHARMA.POS_SUPPLIER_INFO s ON s.PID_SUPPLIER = t.PID_SUP_CUST
                 WHERE t.PID_ORG = :orgId AND t.PID_BRANCH = :branchId
                   AND t.TRANSACTION_TYPE = 'PU'
                   AND (UPPER(t.INVOICE_NO) LIKE :q OR UPPER(s.SUPPLIER_NAME) LIKE :q)
                 ORDER BY t.TRANSACTION_DATE DESC, t.PID_TRAN_MST DESC
                """, orgId, branchId, like));
        }

        if (permissions.has(MOD_REQUISITION, PermissionGuard.Right.VIEW)) {
            out.addAll(run("""
                SELECT 'Requisition', r.PID_REQUISITION, r.REQUISITION_NO, r.STAGE, 'requisition'
                  FROM PHARMA.POS_REQUISITION_MST r
                 WHERE r.PID_ORG = :orgId AND r.PID_BRANCH = :branchId
                   AND UPPER(r.REQUISITION_NO) LIKE :q
                 ORDER BY r.PID_REQUISITION DESC
                """, orgId, branchId, like));
        }

        return out;
    }

    /**
     * Runs one search and shapes its rows.
     *
     * <p>Deliberately not wrapped in a catch. The version this replaces swallowed
     * every exception and returned an empty list, which is how five queries
     * written against the wrong column names survived: a broken search and a
     * search with no matches looked identical from the screen. A query that
     * cannot run is a fault and should say so.
     */
    private List<Map<String, Object>> run(String sql, Long orgId, Long branchId, String like) {
        Query q = em.createNativeQuery(sql)
                .setParameter("orgId", orgId)
                .setParameter("branchId", branchId)
                .setParameter("q", like);
        q.setMaxResults(MAX_PER_TYPE);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type",  str(r[0]));
            m.put("id",    r[1] == null ? null : Long.parseLong(r[1].toString()));
            m.put("label", str(r[2]));
            m.put("sub",   str(r[3]));
            // The page key the app navigates by, not a URL. The old code built
            // '/products/12' — a path this application has no route for, since
            // it navigates by page key rather than by URL.
            m.put("link",  str(r[4]));
            out.add(m);
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
