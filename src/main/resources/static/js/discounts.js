// Discounts page JavaScript

document.addEventListener('DOMContentLoaded', function() {
    loadDiscounts();
});

async function loadDiscounts() {
    const loadingEl = document.getElementById('loading');
    const noDiscountsEl = document.getElementById('no-discounts');
    const containerEl = document.getElementById('discounts-container');

    try {
        const response = await fetch('/api/discounts');
        if (!response.ok) {
            throw new Error('Failed to load discounts');
        }

        const discounts = await response.json();

        loadingEl.style.display = 'none';

        if (discounts.length === 0) {
            noDiscountsEl.style.display = 'block';
            return;
        }

        // Group discounts by brand
        const groupedByBrand = groupByBrand(discounts);

        containerEl.style.display = 'block';
        containerEl.innerHTML = Object.entries(groupedByBrand)
            .map(([brandId, brandData]) => createBrandSection(brandData))
            .join('');

    } catch (error) {
        console.error('Error loading discounts:', error);
        loadingEl.innerHTML = '<p class="error">Failed to load discounts. Please try again later.</p>';
    }
}

// Group discounts by brand
function groupByBrand(discounts) {
    const grouped = {};

    discounts.forEach(discount => {
        const brandId = discount.brandId || 'unknown';
        if (!grouped[brandId]) {
            grouped[brandId] = {
                brandId: discount.brandId,
                brandName: discount.brandName || 'Unknown Brand',
                brandWebsite: discount.brandWebsite,
                discounts: []
            };
        }
        grouped[brandId].discounts.push(discount);
    });

    return grouped;
}

// Create a brand section with all its discounts (collapsible)
function createBrandSection(brandData) {
    const discountItems = brandData.discounts.map(discount => createDiscountItem(discount)).join('');
    const sectionId = `brand-${brandData.brandId || 'unknown'}`;

    return `
        <div class="brand-discount-section" id="${sectionId}">
            <div class="brand-discount-header" onclick="toggleBrandSection('${sectionId}')">
                <div class="brand-info">
                    <span class="collapse-icon">▼</span>
                    <h3 class="brand-name">${escapeHtml(brandData.brandName)}</h3>
                    <span class="discount-count">${brandData.discounts.length} offer${brandData.discounts.length > 1 ? 's' : ''}</span>
                </div>
                <div class="brand-actions" onclick="event.stopPropagation()">
                    ${brandData.brandId ? `<a href="/products.html?brandId=${brandData.brandId}" class="btn-view-products">View Products</a>` : ''}
                    ${brandData.brandWebsite ? `<a href="${escapeHtml(brandData.brandWebsite)}" target="_blank" rel="noopener" class="btn-visit-store">Visit Store →</a>` : ''}
                </div>
            </div>
            <div class="brand-discount-list">
                ${discountItems}
            </div>
        </div>
    `;
}

// Toggle brand section collapse
function toggleBrandSection(sectionId) {
    const section = document.getElementById(sectionId);
    if (section) {
        section.classList.toggle('collapsed');
    }
}

// Create a single discount item within a brand section
function createDiscountItem(discount) {
    const hasCode = discount.discountCode && discount.discountCode.trim() && discount.discountCode !== 'null';

    return `
        <div class="discount-item">
            <div class="discount-item-content">
                <span class="discount-icon">🏷️</span>
                <div class="discount-details">
                    <p class="discount-title">${escapeHtml(discount.title)}</p>
                    ${discount.description && discount.description !== discount.title && discount.description !== 'null' ? `<p class="discount-description">${escapeHtml(discount.description)}</p>` : ''}
                </div>
            </div>
            ${hasCode ? `
                <div class="discount-code-container">
                    <code class="discount-code" onclick="copyCode('${escapeHtml(discount.discountCode)}')">${escapeHtml(discount.discountCode)}</code>
                    <button class="copy-btn" onclick="copyCode('${escapeHtml(discount.discountCode)}')">Copy</button>
                </div>
            ` : ''}
        </div>
    `;
}

function copyCode(code) {
    navigator.clipboard.writeText(code).then(() => {
        // Show toast notification
        showToast('Code copied: ' + code);
    }).catch(err => {
        console.error('Failed to copy:', err);
    });
}

function showToast(message) {
    // Create toast element
    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.textContent = message;
    document.body.appendChild(toast);

    // Trigger animation
    setTimeout(() => toast.classList.add('show'), 10);

    // Remove after 2 seconds
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300);
    }, 2000);
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
