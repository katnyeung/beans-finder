package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.BrandApproval;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.repository.BrandApprovalRepository;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for managing brand approval workflow
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BrandApprovalService {

    private final BrandApprovalRepository approvalRepository;
    private final CoffeeBrandRepository brandRepository;

    /**
     * Submit a new brand for approval
     */
    @Transactional
    public BrandApproval submitBrandForApproval(
            String brandName,
            String website,
            String sitemapUrl,
            String country,
            String description,
            String submittedBy,
            String submissionNotes) {
        return submitBrandForApproval(brandName, website, sitemapUrl, country, null, null, null,
                description, submittedBy, submissionNotes, null, null);
    }

    public BrandApproval submitBrandForApproval(
            String brandName,
            String website,
            String sitemapUrl,
            String country,
            String city,
            String address,
            String postcode,
            String description,
            String submittedBy,
            String submissionNotes,
            Double latitude,
            Double longitude) {

        // Check if brand already exists
        if (brandRepository.existsByName(brandName)) {
            throw new IllegalArgumentException("Brand already exists: " + brandName);
        }

        // Create brand entity (pending approval)
        CoffeeBrand brand = CoffeeBrand.builder()
                .name(brandName)
                .website(website)
                .sitemapUrl(sitemapUrl)
                .country(country)
                .city(city)
                .address(address)
                .postcode(postcode)
                .description(description)
                .latitude(latitude)
                .longitude(longitude)
                .coordinatesValidated(latitude != null && longitude != null)
                .status("pending_approval")
                .approved(false)
                .build();

        brand = brandRepository.save(brand);
        log.info("Created brand pending approval: {} (ID: {})", brandName, brand.getId());

        // Create approval request
        BrandApproval approval = BrandApproval.builder()
                .brand(brand)
                .submittedBy(submittedBy)
                .requestedBrandName(brandName)
                .requestedWebsite(website)
                .requestedCountry(country)
                .requestedDescription(description)
                .submissionNotes(submissionNotes)
                .status("pending")
                .build();

        approval = approvalRepository.save(approval);
        log.info("Created approval request for brand: {} (Approval ID: {})", brandName, approval.getId());

        return approval;
    }

    /**
     * Approve a brand
     */
    @Transactional
    public void approveBrand(Long approvalId, String reviewedBy, String reviewNotes) {
        BrandApproval approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));

        if (!approval.getStatus().equals("pending")) {
            throw new IllegalStateException("Approval is not pending: " + approval.getStatus());
        }

        // Update approval
        approval.setStatus("approved");
        approval.setReviewedBy(reviewedBy);
        approval.setReviewNotes(reviewNotes);
        approval.setReviewedDate(LocalDateTime.now());
        approvalRepository.save(approval);

        // Update brand
        CoffeeBrand brand = approval.getBrand();
        brand.setApproved(true);
        brand.setStatus("active");
        brand.setApprovedBy(reviewedBy);
        brand.setApprovedDate(LocalDateTime.now());
        brandRepository.save(brand);

        log.info("Approved brand: {} by {}", brand.getName(), reviewedBy);
    }

    /**
     * Reject a brand
     */
    @Transactional
    public void rejectBrand(Long approvalId, String reviewedBy, String reviewNotes) {
        BrandApproval approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + approvalId));

        if (!approval.getStatus().equals("pending")) {
            throw new IllegalStateException("Approval is not pending: " + approval.getStatus());
        }

        // Update approval
        approval.setStatus("rejected");
        approval.setReviewedBy(reviewedBy);
        approval.setReviewNotes(reviewNotes);
        approval.setReviewedDate(LocalDateTime.now());
        approvalRepository.save(approval);

        // Update brand status
        CoffeeBrand brand = approval.getBrand();
        brand.setStatus("rejected");
        brandRepository.save(brand);

        log.info("Rejected brand: {} by {}", brand.getName(), reviewedBy);
    }

    /**
     * Get all pending approvals
     */
    public List<BrandApproval> getPendingApprovals() {
        return approvalRepository.findByStatusOrderBySubmittedDateAsc("pending");
    }

    /**
     * Get approvals by submitter
     */
    public List<BrandApproval> getApprovalsBySubmitter(String submittedBy) {
        return approvalRepository.findBySubmittedBy(submittedBy);
    }

    /**
     * Get approval status for a brand
     */
    public BrandApproval getApprovalForBrand(CoffeeBrand brand) {
        return approvalRepository.findByBrand(brand)
                .orElse(null);
    }
}
