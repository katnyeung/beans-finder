// API Base URL
const API_BASE = '/api';

// Store brands data for sorting
let brandsData = [];
let currentSort = { column: null, ascending: true };
let searchTimeout = null;

// Load brands on page load
document.addEventListener('DOMContentLoaded', () => {
    loadNewProducts();
    loadBrands();
    setupProductSearch();
    setupSuggestBrandModal();
});

// Load new products (last 7 days)
async function loadNewProducts() {
    const section = document.getElementById('new-products-section');
    const container = document.getElementById('new-products-container');
    const countBadge = document.getElementById('new-products-count');

    try {
        const response = await fetch(`${API_BASE}/products/new?days=7`);

        if (!response.ok) {
            console.error('Failed to load new products');
            return;
        }

        const products = await response.json();

        if (products.length === 0) {
            // No new products, hide section
            section.style.display = 'none';
            return;
        }

        // Show section and update count
        section.style.display = 'block';
        countBadge.textContent = `${products.length} new`;

        // Render product cards
        container.innerHTML = '';
        products.slice(0, 12).forEach(product => {
            const card = createNewProductCard(product);
            container.appendChild(card);
        });

    } catch (err) {
        console.error('Error loading new products:', err);
    }
}

// Toggle new products section collapse
function toggleNewProducts() {
    const container = document.getElementById('new-products-container');
    const icon = document.getElementById('collapse-icon');

    if (container.classList.contains('collapsed')) {
        container.classList.remove('collapsed');
        icon.classList.remove('collapsed');
    } else {
        container.classList.add('collapsed');
        icon.classList.add('collapsed');
    }
}

function createNewProductCard(product) {
    const card = document.createElement('div');
    card.className = 'new-product-card';

    // Format price with proper currency symbol and decimals
    let priceText = '';
    const currencySymbol = (product.currency === 'GBP' || !product.currency) ? '£' : product.currency + ' ';

    if (product.priceVariantsJson) {
        try {
            const variants = typeof product.priceVariantsJson === 'string'
                ? JSON.parse(product.priceVariantsJson)
                : product.priceVariantsJson;
            if (Array.isArray(variants) && variants.length > 0 && variants[0].price != null) {
                priceText = `${currencySymbol}${parseFloat(variants[0].price).toFixed(2)}`;
            }
        } catch (e) {}
    }
    if (!priceText && product.price) {
        priceText = `${currencySymbol}${parseFloat(product.price).toFixed(2)}`;
    }

    // Format date
    let dateText = '';
    if (product.createdDate) {
        const date = new Date(product.createdDate);
        const now = new Date();
        const diffDays = Math.floor((now - date) / (1000 * 60 * 60 * 24));
        if (diffDays === 0) {
            dateText = 'Added today';
        } else if (diffDays === 1) {
            dateText = 'Added yesterday';
        } else {
            dateText = `Added ${diffDays} days ago`;
        }
    }

    card.innerHTML = `
        <a href="/product-detail.html?id=${product.id}" class="product-name">${escapeHtml(product.productName)}</a>
        <div class="product-brand">${escapeHtml(product.brandName || 'Unknown Brand')}</div>
        <div class="product-meta">
            ${priceText ? `<span class="product-price">${priceText}</span>` : ''}
            ${product.origin ? `<span class="product-origin">${escapeHtml(product.origin)}</span>` : ''}
            ${product.roastLevel ? `<span>${escapeHtml(product.roastLevel)}</span>` : ''}
        </div>
        ${dateText ? `<div class="product-date">${dateText}</div>` : ''}
    `;

    return card;
}

// Load all brands
async function loadBrands() {
    const container = document.getElementById('brandsContainer');
    const loading = document.getElementById('loading');
    const error = document.getElementById('error');

    try {
        loading.style.display = 'block';
        error.style.display = 'none';
        container.innerHTML = '';

        const response = await fetch(`${API_BASE}/brands/approved`);

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        brandsData = await response.json();
        loading.style.display = 'none';

        if (brandsData.length === 0) {
            container.innerHTML = `
                <tr>
                    <td colspan="5" class="empty-state">No brands found</td>
                </tr>
            `;
            return;
        }

        renderBrands(brandsData);

    } catch (err) {
        console.error('Error loading brands:', err);
        loading.style.display = 'none';
        error.style.display = 'block';
        error.textContent = `Failed to load brands: ${err.message}`;
    }
}

// Render brands in the table
function renderBrands(brands) {
    const container = document.getElementById('brandsContainer');
    container.innerHTML = '';

    brands.forEach(brand => {
        const row = createBrandRow(brand);
        container.appendChild(row);
    });
}

// Create a brand table row element
function createBrandRow(brand) {
    const row = document.createElement('tr');
    row.className = 'clickable-row';
    row.onclick = () => goToProducts(brand.id);
    row.dataset.productCount = brand.productCount || 0;
    row.dataset.lastCrawl = brand.lastCrawlDate || '';

    const lastCrawl = brand.lastCrawlDate
        ? new Date(brand.lastCrawlDate).toLocaleDateString()
        : 'Never';

    const productCount = brand.productCount !== null && brand.productCount !== undefined
        ? brand.productCount
        : 0;

    row.innerHTML = `
        <td class="brand-name">${escapeHtml(brand.name)}</td>
        <td>${escapeHtml(brand.country || 'N/A')}</td>
        <td>
            ${brand.website
                ? `<a href="${escapeHtml(brand.website)}" target="_blank" onclick="event.stopPropagation()">Visit</a>`
                : 'N/A'}
        </td>
        <td>${productCount}</td>
        <td>${lastCrawl}</td>
    `;

    return row;
}

// Navigate to products page
function goToProducts(brandId) {
    window.location.href = `/products.html?brandId=${brandId}`;
}

// Sort table by column
function sortTable(column) {
    // Toggle sort direction if clicking same column
    if (currentSort.column === column) {
        currentSort.ascending = !currentSort.ascending;
    } else {
        currentSort.column = column;
        currentSort.ascending = false; // Start with descending for numbers
    }

    // Sort the brands data
    const sortedBrands = [...brandsData].sort((a, b) => {
        let aVal, bVal;

        if (column === 'productCount') {
            aVal = a.productCount || 0;
            bVal = b.productCount || 0;
        } else if (column === 'lastCrawl') {
            aVal = a.lastCrawlDate ? new Date(a.lastCrawlDate).getTime() : 0;
            bVal = b.lastCrawlDate ? new Date(b.lastCrawlDate).getTime() : 0;
        }

        if (currentSort.ascending) {
            return aVal - bVal;
        } else {
            return bVal - aVal;
        }
    });

    // Update sort indicators
    document.querySelectorAll('.sort-indicator').forEach(el => el.textContent = '');
    const indicator = document.getElementById(`sort-${column}`);
    if (indicator) {
        indicator.textContent = currentSort.ascending ? ' ▲' : ' ▼';
    }

    // Re-render table
    renderBrands(sortedBrands);
}

// ==================== Product Search ====================

function setupProductSearch() {
    const searchInput = document.getElementById('product-search');
    if (!searchInput) return;

    searchInput.addEventListener('input', (e) => {
        clearTimeout(searchTimeout);
        const query = e.target.value.trim();

        if (query.length < 2) {
            hideSearchResults();
            return;
        }

        searchTimeout = setTimeout(() => {
            searchProducts(query);
        }, 300);
    });

    // Handle Enter key
    searchInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            const query = e.target.value.trim();
            if (query.length >= 2) {
                clearTimeout(searchTimeout);
                searchProducts(query);
            }
        }
    });
}

async function searchProducts(query) {
    const resultsSection = document.getElementById('search-results');
    const resultsTitle = document.getElementById('search-results-title');
    const resultsContainer = document.getElementById('searchResultsContainer');
    const brandsSection = document.getElementById('brands-section');

    try {
        const response = await fetch(`${API_BASE}/products/search?query=${encodeURIComponent(query)}&limit=20`);

        if (!response.ok) {
            throw new Error('Search failed');
        }

        const products = await response.json();

        if (products.length === 0) {
            resultsTitle.textContent = `No products found for "${query}"`;
            resultsContainer.innerHTML = `
                <tr>
                    <td colspan="6" class="empty-state">No products match your search. Try the AI Chat for more options.</td>
                </tr>
            `;
        } else {
            resultsTitle.textContent = `Found ${products.length} product${products.length > 1 ? 's' : ''} for "${query}"`;
            resultsContainer.innerHTML = '';

            products.forEach(product => {
                const row = createProductSearchRow(product);
                resultsContainer.appendChild(row);
            });
        }

        resultsSection.style.display = 'block';
        brandsSection.style.display = 'none';

    } catch (err) {
        console.error('Error searching products:', err);
        resultsTitle.textContent = 'Search Error';
        resultsContainer.innerHTML = `
            <tr>
                <td colspan="6" class="empty-state">Failed to search products. Please try again.</td>
            </tr>
        `;
        resultsSection.style.display = 'block';
    }
}

function createProductSearchRow(product) {
    const row = document.createElement('tr');

    // Format price with proper currency symbol and decimals
    let priceText = 'N/A';
    const currencySymbol = (product.currency === 'GBP' || !product.currency) ? '£' : product.currency + ' ';

    if (product.priceVariantsJson) {
        try {
            const variants = typeof product.priceVariantsJson === 'string'
                ? JSON.parse(product.priceVariantsJson)
                : product.priceVariantsJson;
            if (Array.isArray(variants) && variants.length > 0) {
                const firstVariant = variants[0];
                if (firstVariant.price != null) {
                    priceText = `${currencySymbol}${parseFloat(firstVariant.price).toFixed(2)}`;
                }
            }
        } catch (e) {}
    }
    if (priceText === 'N/A' && product.price) {
        priceText = `${currencySymbol}${parseFloat(product.price).toFixed(2)}`;
    }

    row.innerHTML = `
        <td><a href="/product-detail.html?id=${product.id}">${escapeHtml(product.productName)}</a></td>
        <td>${escapeHtml(product.brandName || 'Unknown')}</td>
        <td>${escapeHtml(product.origin || 'N/A')}</td>
        <td>${escapeHtml(product.roastLevel || 'N/A')}</td>
        <td>${priceText}</td>
        <td>
            <a href="/product-detail.html?id=${product.id}" class="btn btn-small">Details</a>
        </td>
    `;

    return row;
}

function hideSearchResults() {
    const resultsSection = document.getElementById('search-results');
    const brandsSection = document.getElementById('brands-section');

    resultsSection.style.display = 'none';
    brandsSection.style.display = 'block';
}

// ==================== Suggest Brand Modal ====================

function setupSuggestBrandModal() {
    const suggestBtn = document.getElementById('suggest-brand-btn');
    const modal = document.getElementById('suggest-brand-modal');
    const form = document.getElementById('suggest-brand-form');

    if (suggestBtn) {
        suggestBtn.addEventListener('click', openSuggestModal);
    }

    // Close modal when clicking outside
    if (modal) {
        modal.addEventListener('click', (e) => {
            if (e.target === modal) {
                closeSuggestModal();
            }
        });
    }

    // Handle form submission
    if (form) {
        form.addEventListener('submit', handleSuggestSubmit);
    }

    // Close modal on Escape key
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            closeSuggestModal();
        }
    });
}

function openSuggestModal() {
    const modal = document.getElementById('suggest-brand-modal');
    if (modal) {
        modal.classList.add('active');
        document.getElementById('brand-name').focus();
    }
}

function closeSuggestModal() {
    const modal = document.getElementById('suggest-brand-modal');
    const form = document.getElementById('suggest-brand-form');
    const message = document.getElementById('form-message');

    if (modal) {
        modal.classList.remove('active');
    }

    // Reset form
    if (form) {
        form.reset();
    }

    // Reset reCAPTCHA
    if (typeof grecaptcha !== 'undefined') {
        grecaptcha.reset();
    }

    // Hide message
    if (message) {
        message.style.display = 'none';
    }
}

async function handleSuggestSubmit(e) {
    e.preventDefault();

    const brandName = document.getElementById('brand-name').value.trim();
    const brandUrl = document.getElementById('brand-url').value.trim();
    const submitBtn = document.getElementById('submit-suggestion-btn');
    const message = document.getElementById('form-message');

    // Get reCAPTCHA response
    let recaptchaToken = '';
    if (typeof grecaptcha !== 'undefined') {
        recaptchaToken = grecaptcha.getResponse();
    }

    if (!recaptchaToken) {
        showFormMessage('Please complete the CAPTCHA verification.', 'error');
        return;
    }

    if (!brandName || !brandUrl) {
        showFormMessage('Please fill in all required fields.', 'error');
        return;
    }

    // Disable submit button
    submitBtn.disabled = true;
    submitBtn.textContent = 'Submitting...';

    try {
        const response = await fetch(`${API_BASE}/brands/suggest`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                name: brandName,
                websiteUrl: brandUrl,
                recaptchaToken: recaptchaToken
            })
        });

        const result = await response.text();

        if (response.ok) {
            showFormMessage('Thank you! Your suggestion has been submitted for review.', 'success');
            setTimeout(() => {
                closeSuggestModal();
            }, 2000);
        } else {
            showFormMessage(result || 'Failed to submit suggestion. Please try again.', 'error');
            // Reset reCAPTCHA on error
            if (typeof grecaptcha !== 'undefined') {
                grecaptcha.reset();
            }
        }

    } catch (err) {
        console.error('Error submitting suggestion:', err);
        showFormMessage('An error occurred. Please try again later.', 'error');
    } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = 'Submit Suggestion';
    }
}

function showFormMessage(text, type) {
    const message = document.getElementById('form-message');
    if (message) {
        message.textContent = text;
        message.className = 'form-message ' + type;
        message.style.display = 'block';
    }
}

// Escape HTML to prevent XSS
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
