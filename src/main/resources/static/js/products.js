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

    // Format tasting notes with maximum defensive checks
    let tastingNotesText = 'N/A';
    try {
        if (tastingNotes && Array.isArray(tastingNotes) && tastingNotes.length > 0) {
            tastingNotesText = tastingNotes.filter(note => note != null && note !== '').join(', ');
            if (!tastingNotesText || tastingNotesText.trim() === '') {
                tastingNotesText = 'N/A';
            }
        }
    } catch (e) {
        console.error('Error formatting tasting notes for product', product.id, ':', e);
        tastingNotesText = 'N/A';
    }

    // Format price
    const priceText = product.price
        ? `${product.currency || 'GBP'} ${product.price}`
        : 'N/A';

    // Format stock status
    const stockStatus = product.inStock ? '✓' : '✗';
    const stockClass = product.inStock ? 'in-stock' : 'out-of-stock';

    row.innerHTML = `
        <td>${product.id}</td>
        <td class="product-name">${escapeHtml(product.productName)}</td>
        <td>${escapeHtml(product.origin || 'N/A')}</td>
        <td>${escapeHtml(product.region || 'N/A')}</td>
        <td>${escapeHtml(product.process || 'N/A')}</td>
        <td>${escapeHtml(product.variety || 'N/A')}</td>
        <td class="tasting-notes-cell" title="${escapeHtml(tastingNotesText)}">
            ${escapeHtml(tastingNotesText)}
        </td>
        <td>${priceText}</td>
        <td class="${stockClass}">${stockStatus}</td>
        <td>
            <button class="btn-chat" onclick="event.stopPropagation(); askChatbotAbout(${product.id}, '${escapeHtml(product.productName).replace(/'/g, "\\'")}')">
                💬 Ask Chatbot
            </button>
        </td>
    `;

    // Make row clickable if there's a seller URL
    if (product.sellerUrl) {
        row.className = 'clickable-row';
        row.onclick = () => window.open(product.sellerUrl, '_blank');
    }

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
