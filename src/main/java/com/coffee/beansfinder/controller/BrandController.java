package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.BrandApproval;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.service.BrandApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for managing coffee brands
 */
@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
@Slf4j
public class BrandController {

    private final CoffeeBrandRepository brandRepository;
    private final BrandApprovalService approvalService;

    /**
     * Get all brands
     */
    @GetMapping
    public List<CoffeeBrand> getAllBrands() {
        return brandRepository.findAll();
    }

    /**
     * Get approved brands only
     */
    @GetMapping("/approved")
    public List<CoffeeBrand> getApprovedBrands() {
        return brandRepository.findByApprovedTrue();
    }

    /**
     * Get brand by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<CoffeeBrand> getBrandById(@PathVariable Long id) {
        return brandRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Submit new brand for approval
     */
    @PostMapping("/submit")
    public ResponseEntity<BrandApproval> submitBrand(@RequestBody BrandSubmissionRequest request) {
        try {
            BrandApproval approval = approvalService.submitBrandForApproval(
                    request.name,
                    request.website,
                    request.country,
                    request.description,
                    request.submittedBy,
                    request.submissionNotes
            );
            return ResponseEntity.ok(approval);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get pending brand approvals
     */
    @GetMapping("/approvals/pending")
    public List<BrandApproval> getPendingApprovals() {
        return approvalService.getPendingApprovals();
    }

    /**
     * Approve a brand
     */
    @PostMapping("/approvals/{id}/approve")
    public ResponseEntity<Void> approveBrand(
            @PathVariable Long id,
            @RequestBody ApprovalDecisionRequest request) {
        try {
            approvalService.approveBrand(id, request.reviewedBy, request.reviewNotes);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to approve brand: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Reject a brand
     */
    @PostMapping("/approvals/{id}/reject")
    public ResponseEntity<Void> rejectBrand(
            @PathVariable Long id,
            @RequestBody ApprovalDecisionRequest request) {
        try {
            approvalService.rejectBrand(id, request.reviewedBy, request.reviewNotes);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to reject brand: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // DTOs
    public record BrandSubmissionRequest(
            String name,
            String website,
            String country,
            String description,
            String submittedBy,
            String submissionNotes
    ) {}

    public record ApprovalDecisionRequest(
            String reviewedBy,
            String reviewNotes
    ) {}
}
