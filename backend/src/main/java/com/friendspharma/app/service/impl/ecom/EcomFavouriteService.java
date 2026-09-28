package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomCatalogueResponses;
import com.friendspharma.app.entity.EcomCustomerFavourite;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.EcomCustomerFavouriteRepository;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The medicines a customer saved for later.
 *
 * <p>The list stores a reference, never a copy: no price, no stock, no name is
 * written here. Everything the screen shows is read back from the catalogue at
 * the moment of asking, priced for whoever is asking — so a favourite saved by
 * a retailer and opened after they become a wholesaler shows the wholesale
 * price, and one saved at ৳4.00 that has since gone up shows the real figure
 * rather than the one that was true when they tapped the heart.
 *
 * <p>A listing that has since been retired is not silently dropped. It comes
 * back marked {@code available: false}, because a customer who saved something
 * is owed the difference between "gone" and "never happened".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomFavouriteService {

    private final EcomCustomerFavouriteRepository favourites;
    private final EcomCatalogueService catalogue;

    private Long me() {
        EcomCustomerPrincipal p = EcomSecurity.require();
        return p.customerId();
    }

    /** Every saved listing, resolved against the catalogue as it stands now. */
    @Transactional(readOnly = true)
    public List<Saved> list() {
        Long customerId = me();
        List<Saved> out = new ArrayList<>();

        for (EcomCustomerFavourite f : favourites.listFor(customerId)) {
            EcomCatalogueResponses.Item item = null;
            try {
                /* At the branch it was saved from. A favourite is a listing,
                   and an org-wide medicine is a different listing with
                   different stock at every branch of its organisation. */
                item = catalogue.get(f.getPidProduct(), f.getPidBranch(), null, null);
            } catch (RuntimeException e) {
                /* Retired, superseded, or the pharmacy went offline. The row
                   stays; the card says so. */
                log.debug("favourite {} no longer resolvable: {}", f.getPidFavourite(), e.getMessage());
            }
            out.add(new Saved(f.getPidFavourite(), f.getPidBranch(), f.getPidProduct(),
                              item != null, item));
        }
        return out;
    }

    /**
     * Which of these listings this customer has saved.
     *
     * <p>Keys are {@code branchId:productId}, so a grid showing the same
     * medicine at three pharmacies lights the heart on the one that was saved
     * and not on the other two.
     */
    @Transactional(readOnly = true)
    public Set<String> keys() {
        return new LinkedHashSet<>(favourites.keysFor(me()));
    }

    /**
     * Save it, or unsave it. One call, because the heart is one control and a
     * client that has to decide which of two endpoints to hit will eventually
     * decide wrongly and lose a tap.
     *
     * @return true when it is now saved
     */
    @Transactional
    public boolean toggle(Long branchId, Long productId) {
        if (branchId == null || productId == null) {
            throw new BusinessException("A medicine is saved at a pharmacy, so both are needed.",
                    HttpStatus.BAD_REQUEST, "ECOM_FAV_INCOMPLETE");
        }
        Long customerId = me();

        var existing = favourites.find(customerId, branchId, productId);
        if (existing.isPresent()) {
            favourites.delete(existing.get());
            return false;
        }

        /* Read it once before saving: a heart on something that is not actually
           listed would put a row in the table that can never render. */
        try {
            catalogue.get(productId, branchId, null, null);
        } catch (RuntimeException e) {
            throw new BusinessException("That medicine is not listed online.",
                    HttpStatus.NOT_FOUND, "ECOM_FAV_NOT_LISTED");
        }

        favourites.save(EcomCustomerFavourite.builder()
                .pidEcomCustomer(customerId).pidBranch(branchId).pidProduct(productId)
                .build());
        return true;
    }

    /** One saved listing: the reference, and the catalogue's answer about it today. */
    public record Saved(Long id, Long branchId, Long productId,
                        boolean available, EcomCatalogueResponses.Item item) { }
}
