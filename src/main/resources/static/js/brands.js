// API Base URL
const API_BASE = '/api';

// Load brands on page load
document.addEventListener('DOMContentLoaded', () => {
    loadBrands();
    setupSearch();
});

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

        const brands = await response.json();
        loading.style.display = 'none';

        if (brands.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <h3>No brands found</h3>
                    <p>There are no approved brands yet.</p>
                </div>
            `;
            return;
        }

        brands.forEach(brand => {
            const card = createBrandCard(brand);
            container.appendChild(card);
        });

    } catch (err) {
        console.error('Error loading brands:', err);
        loading.style.display = 'none';
        error.style.display = 'block';
        error.textContent = `Failed to load brands: ${err.message}`;
    }
}

// Create a brand card element
function createBrandCard(brand) {
    const card = document.createElement('div');
    card.className = 'brand-card';
    card.onclick = () => goToProducts(brand.id);

    const statusClass = brand.approved ? 'status-approved' : 'status-pending';
    const statusText = brand.approved ? 'Approved' : 'Pending';

    card.innerHTML = `
        <h3>${escapeHtml(brand.name)}</h3>
        <div class="brand-info">
            <div>
                <strong>📍 Country:</strong> ${escapeHtml(brand.country || 'N/A')}
            </div>
            <div>
                <strong>🌐 Website:</strong>
                ${brand.website ? `<a href="${escapeHtml(brand.website)}" target="_blank" onclick="event.stopPropagation()">Visit</a>` : 'N/A'}
            </div>
            <div>
                <strong>Status:</strong>
                <span class="brand-status ${statusClass}">${statusText}</span>
            </div>
            ${brand.description ? `<div style="margin-top: 0.5rem; color: #666;">${escapeHtml(brand.description)}</div>` : ''}
        </div>
    `;

    return card;
}

// Navigate to products page
function goToProducts(brandId) {
    window.location.href = `/products.html?brandId=${brandId}`;
}

// Setup search functionality
function setupSearch() {
    const searchInput = document.getElementById('searchInput');
    let timeoutId;

    searchInput.addEventListener('input', (e) => {
        clearTimeout(timeoutId);
        timeoutId = setTimeout(() => {
            filterBrands(e.target.value.toLowerCase());
        }, 300);
    });
}

// Filter brands by search term
function filterBrands(searchTerm) {
    const cards = document.querySelectorAll('.brand-card');
    let visibleCount = 0;

    cards.forEach(card => {
        const brandName = card.querySelector('h3').textContent.toLowerCase();
        const brandInfo = card.querySelector('.brand-info').textContent.toLowerCase();

        if (brandName.includes(searchTerm) || brandInfo.includes(searchTerm)) {
            card.style.display = 'block';
            visibleCount++;
        } else {
            card.style.display = 'none';
        }
    });

    // Show empty state if no results
    const container = document.getElementById('brandsContainer');
    const existingEmpty = container.querySelector('.empty-state');

    if (visibleCount === 0 && searchTerm && !existingEmpty) {
        const emptyDiv = document.createElement('div');
        emptyDiv.className = 'empty-state';
        emptyDiv.innerHTML = `
            <h3>No matches found</h3>
            <p>No brands match your search term: "${escapeHtml(searchTerm)}"</p>
        `;
        container.appendChild(emptyDiv);
    } else if (visibleCount > 0 && existingEmpty) {
        existingEmpty.remove();
    }
}

// Escape HTML to prevent XSS
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
