// API Base URL
const API_BASE = '/api';

// Get brand ID from URL
const urlParams = new URLSearchParams(window.location.search);
const brandId = urlParams.get('brandId');

// Load products on page load
document.addEventListener('DOMContentLoaded', () => {
    if (!brandId) {
        showError('No brand ID specified');
        return;
    }

    loadBrand();
    loadProducts();
    loadBrandDiscount();
});

// Load brand information
async function loadBrand() {
    try {
        const response = await fetch(`${API_BASE}/brands/${brandId}`);

        if (!response.ok) {
            throw new Error('Brand not found');
        }

        const brand = await response.json();

        document.getElementById('brandName').textContent = brand.name;
        document.getElementById('brandInfo').textContent = brand.description ||
            `Specialty coffee roaster${brand.country ? ' from ' + brand.country : ''}`;

    } catch (err) {
        console.error('Error loading brand:', err);
        document.getElementById('brandName').textContent = 'Brand Not Found';
        document.getElementById('brandInfo').textContent = 'Unable to load brand information';
    }
}

// Load products for this brand
async function loadProducts() {
    const container = document.getElementById('productsContainer');
    const loading = document.getElementById('loading');
    const error = document.getElementById('error');

    try {
        loading.style.display = 'block';
        error.style.display = 'none';
        container.innerHTML = '';

        const response = await fetch(`${API_BASE}/products/brand/${brandId}`);

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const products = await response.json();
        loading.style.display = 'none';

        if (!products || products.length === 0) {
            container.innerHTML = `
                <tr>
                    <td colspan="9" class="empty-state">No products found for this brand</td>
                </tr>
            `;
            return;
        }

        products.forEach((product, index) => {
            try {
                const row = createProductRow(product);
                container.appendChild(row);
            } catch (err) {
                console.error(`Error creating row for product ${product.id || index}:`, err);
                console.error('Product data:', product);
                // Continue with other products
            }
        });

    } catch (err) {
        console.error('Error loading products:', err);
        loading.style.display = 'none';
        error.style.display = 'block';
        error.textContent = `Failed to load products: ${err.message}`;
    }
}

// Create a product table row element
function createProductRow(product) {
    const row = document.createElement('tr');

    // Parse tasting notes with defensive checks
    let tastingNotes = [];
    try {
        if (product.tastingNotesJson !== null && product.tastingNotesJson !== undefined && product.tastingNotesJson !== '') {
            const parsed = JSON.parse(product.tastingNotesJson);
            // Ensure parsed is an array
            if (Array.isArray(parsed)) {
                tastingNotes = parsed;
            }
        }
    } catch (e) {
        console.error('Error parsing tasting notes for product', product.id, ':', e);
        tastingNotes = [];
    }

    // Format tasting notes as clickable badge HTML (links to discover page with flavor param)
    let tastingNotesHtml = '<span class="na">N/A</span>';
    try {
        if (tastingNotes && Array.isArray(tastingNotes) && tastingNotes.length > 0) {
            const validNotes = tastingNotes.filter(note => note != null && note !== '');
            if (validNotes.length > 0) {
                tastingNotesHtml = validNotes.map(note =>
                    `<a href="/discover.html?flavor=${encodeURIComponent(note)}" class="tasting-note-badge" title="Explore ${escapeHtml(note)}">${escapeHtml(note)}</a>`
                ).join('');
            }
        }
    } catch (e) {
        console.error('Error formatting tasting notes for product', product.id, ':', e);
        tastingNotesHtml = '<span class="na">N/A</span>';
    }

    // Format price with variants as mini table
    let priceHtml = '<span class="na">N/A</span>';
    if (product.priceVariantsJson) {
        try {
            const variants = typeof product.priceVariantsJson === 'string'
                ? JSON.parse(product.priceVariantsJson)
                : product.priceVariantsJson;
            if (Array.isArray(variants) && variants.length > 0) {
                const currency = product.currency || 'GBP';
                const rows = variants
                    .filter(v => v.size) // Only show variants with size
                    .map(v => {
                        const priceDisplay = v.price != null ? `${currency} ${v.price}` : '-';
                        return `<tr><td>${escapeHtml(v.size)}</td><td>${priceDisplay}</td></tr>`;
                    })
                    .join('');
                if (rows) {
                    priceHtml = `<table class="price-table"><tbody>${rows}</tbody></table>`;
                }
            } else if (product.price) {
                priceHtml = `${product.currency || 'GBP'} ${product.price}`;
            }
        } catch (e) {
            priceHtml = product.price ? `${product.currency || 'GBP'} ${product.price}` : '<span class="na">N/A</span>';
        }
    } else if (product.price) {
        priceHtml = `${product.currency || 'GBP'} ${product.price}`;
    }

    // Format stock status
    const stockStatus = product.inStock ? '✓' : '✗';
    const stockClass = product.inStock ? 'in-stock' : 'out-of-stock';

    row.innerHTML = `
        <td>${product.id}</td>
        <td class="product-name">
            <a href="/product-detail.html?id=${product.id}" class="product-name-link">${escapeHtml(product.productName)}</a>
        </td>
        <td>${escapeHtml(product.origin || 'N/A')}</td>
        <td>${escapeHtml(product.region || 'N/A')}</td>
        <td>${escapeHtml(product.process || 'N/A')}</td>
        <td>${escapeHtml(product.variety || 'N/A')}</td>
        <td class="tasting-notes-cell">
            ${tastingNotesHtml}
        </td>
        <td class="price-cell">${priceHtml}</td>
        <td class="${stockClass}">${stockStatus}</td>
    `;

    return row;
}

// Show error message
function showError(message) {
    const error = document.getElementById('error');
    error.style.display = 'block';
    error.textContent = message;
    document.getElementById('loading').style.display = 'none';
}

// Capitalize first letter
function capitalize(str) {
    return str.charAt(0).toUpperCase() + str.slice(1);
}

// Escape HTML to prevent XSS
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Ask chatbot about a product
function askChatbotAbout(productId, productName) {
    console.log('Ask chatbot about:', productId, productName);

    // Call setReferenceProduct from chatbot.js
    if (typeof setReferenceProduct === 'function') {
        setReferenceProduct(productId, productName);
    } else {
        console.error('chatbot.js not loaded');
        alert('Chatbot is not available. Please refresh the page.');
    }
}

// Load brand discounts and display banner if available
async function loadBrandDiscount() {
    if (!brandId) return;

    try {
        const response = await fetch(`${API_BASE}/discounts/brand/${brandId}`);
        if (!response.ok) return;

        const discounts = await response.json();
        if (!discounts || discounts.length === 0) return;

        const banner = document.getElementById('brand-discount-banner');
        const textEl = document.getElementById('discount-text');
        const codeEl = document.getElementById('discount-code-badge');

        // Build HTML for all discounts
        const discountHtml = discounts.map(discount => {
            const hasCode = discount.discountCode && discount.discountCode.trim() && discount.discountCode !== 'null';
            const hasDesc = discount.description && discount.description !== discount.title && discount.description !== 'null';

            let html = `<div class="discount-item-inline">
                <strong>${escapeHtml(discount.title)}</strong>`;

            if (hasDesc) {
                html += `<span class="discount-desc-inline"> - ${escapeHtml(discount.description)}</span>`;
            }

            if (hasCode) {
                html += ` <code class="discount-code-inline" onclick="copyDiscountCode('${escapeHtml(discount.discountCode)}')">${escapeHtml(discount.discountCode)}</code>`;
            }

            html += `</div>`;
            return html;
        }).join('');

        textEl.innerHTML = discountHtml;
        codeEl.style.display = 'none'; // Hide the old single code badge

        banner.style.display = 'block';

    } catch (error) {
        console.debug('No discounts for brand:', error);
    }
}

// Copy discount code helper
function copyDiscountCode(code) {
    navigator.clipboard.writeText(code).then(() => {
        // Brief visual feedback
        const el = event.target;
        const original = el.textContent;
        el.textContent = 'Copied!';
        setTimeout(() => { el.textContent = original; }, 1500);
    });
}
