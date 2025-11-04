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

        if (products.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <h3>No products found</h3>
                    <p>This brand doesn't have any products yet.</p>
                    <a href="/brands.html" class="btn btn-primary">Back to Brands</a>
                </div>
            `;
            return;
        }

        products.forEach(product => {
            const card = createProductCard(product);
            container.appendChild(card);
        });

    } catch (err) {
        console.error('Error loading products:', err);
        loading.style.display = 'none';
        error.style.display = 'block';
        error.textContent = `Failed to load products: ${err.message}`;
    }
}

// Create a product card element
function createProductCard(product) {
    const card = document.createElement('div');
    card.className = 'product-card';

    // Parse tasting notes
    let tastingNotes = [];
    try {
        if (product.tastingNotesJson) {
            tastingNotes = JSON.parse(product.tastingNotesJson);
        }
    } catch (e) {
        console.error('Error parsing tasting notes:', e);
    }

    // Parse SCA flavors
    let scaFlavors = {};
    try {
        if (product.scaFlavorsJson) {
            scaFlavors = JSON.parse(product.scaFlavorsJson);
        }
    } catch (e) {
        console.error('Error parsing SCA flavors:', e);
    }

    // Build tasting notes HTML
    let tastingNotesHtml = '';
    if (tastingNotes.length > 0) {
        tastingNotesHtml = `
            <div class="tasting-notes">
                <h4>Tasting Notes:</h4>
                <div class="notes-list">
                    ${tastingNotes.map(note =>
                        `<span class="note-tag">${escapeHtml(note)}</span>`
                    ).join('')}
                </div>
            </div>
        `;
    }

    // Build SCA flavors HTML
    let scaHtml = '';
    if (Object.keys(scaFlavors).length > 0) {
        const flavorCategories = Object.entries(scaFlavors)
            .filter(([_, notes]) => notes && notes.length > 0)
            .map(([category, notes]) =>
                `<div><strong>${capitalize(category)}:</strong> ${notes.join(', ')}</div>`
            ).join('');

        if (flavorCategories) {
            scaHtml = `
                <div class="tasting-notes">
                    <h4>SCA Flavor Profile:</h4>
                    <div style="font-size: 0.85rem; color: #666;">
                        ${flavorCategories}
                    </div>
                </div>
            `;
        }
    }

    card.innerHTML = `
        <h3>${escapeHtml(product.productName)}</h3>
        <div class="product-details">
            ${product.origin ? `<div><strong>Origin:</strong> ${escapeHtml(product.origin)}${product.region ? ', ' + escapeHtml(product.region) : ''}</div>` : ''}
            ${product.process ? `<div><strong>Process:</strong> ${escapeHtml(product.process)}</div>` : ''}
            ${product.variety ? `<div><strong>Variety:</strong> ${escapeHtml(product.variety)}</div>` : ''}
            ${product.producer ? `<div><strong>Producer:</strong> ${escapeHtml(product.producer)}</div>` : ''}
            ${product.altitude ? `<div><strong>Altitude:</strong> ${escapeHtml(product.altitude)}</div>` : ''}
            ${product.price ? `<div><strong>Price:</strong> ${product.currency || 'GBP'} ${product.price}</div>` : ''}
            ${product.inStock !== null ? `<div><strong>Stock:</strong> ${product.inStock ? '✅ In Stock' : '❌ Out of Stock'}</div>` : ''}
        </div>
        ${tastingNotesHtml}
        ${scaHtml}
        ${product.sellerUrl ? `
            <div style="margin-top: 1rem;">
                <a href="${escapeHtml(product.sellerUrl)}" target="_blank" class="btn btn-primary" style="width: 100%;">
                    View Product
                </a>
            </div>
        ` : ''}
    `;

    return card;
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
