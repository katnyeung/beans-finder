package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.BrandApproval;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.service.BrandApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
@Tag(name = "Brands", description = "Coffee brand management and approval workflow")
public class BrandController {

    private final CoffeeBrandRepository brandRepository;
    private final BrandApprovalService approvalService;

    @Operation(
        summary = "Get all brands",
        description = "Returns a list of all coffee brands (approved and pending)"
    )
    @GetMapping
    public List<CoffeeBrand> getAllBrands() {
        return brandRepository.findAll();
    }

    @Operation(
        summary = "Get approved brands",
        description = "Returns only approved and active coffee brands"
    )
    @GetMapping("/approved")
    public List<CoffeeBrand> getApprovedBrands() {
        return brandRepository.findByApprovedTrue();
    }

    @Operation(
        summary = "Get brand by ID",
        description = "Returns a specific coffee brand by its ID"
    )
    @ApiResponse(responseCode = "200", description = "Brand found")
    @ApiResponse(responseCode = "404", description = "Brand not found")
    @GetMapping("/{id}")
    public ResponseEntity<CoffeeBrand> getBrandById(
            @Parameter(description = "Brand ID") @PathVariable Long id) {
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
