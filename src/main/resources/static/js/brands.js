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
                <tr>
                    <td colspan="6" class="empty-state">No brands found</td>
                </tr>
            `;
            return;
        }

        brands.forEach(brand => {
            const row = createBrandRow(brand);
            container.appendChild(row);
        });

    } catch (err) {
        console.error('Error loading brands:', err);
        loading.style.display = 'none';
        error.style.display = 'block';
        error.textContent = `Failed to load brands: ${err.message}`;
    }
}

// Create a brand table row element
function createBrandRow(brand) {
    const row = document.createElement('tr');
    row.className = 'clickable-row';
    row.onclick = () => goToProducts(brand.id);

    const statusClass = brand.approved ? 'status-approved' : 'status-pending';
    const statusText = brand.status || 'active';
    const lastCrawl = brand.lastCrawlDate
        ? new Date(brand.lastCrawlDate).toLocaleDateString()
        : 'Never';

    row.innerHTML = `
        <td>${brand.id}</td>
        <td class="brand-name">${escapeHtml(brand.name)}</td>
        <td>${escapeHtml(brand.country || 'N/A')}</td>
        <td>
            ${brand.website
                ? `<a href="${escapeHtml(brand.website)}" target="_blank" onclick="event.stopPropagation()">Visit</a>`
                : 'N/A'}
        </td>
        <td><span class="status-badge ${statusClass}">${statusText}</span></td>
        <td>${lastCrawl}</td>
    `;

    return row;
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
    const rows = document.querySelectorAll('.clickable-row');
    let visibleCount = 0;

    rows.forEach(row => {
        const text = row.textContent.toLowerCase();

        if (text.includes(searchTerm)) {
            row.style.display = '';
            visibleCount++;
        } else {
            row.style.display = 'none';
        }
    });

    // Show empty state if no results
    const container = document.getElementById('brandsContainer');
    const existingEmpty = container.querySelector('.empty-state');

    if (visibleCount === 0 && searchTerm && !existingEmpty) {
        const emptyRow = document.createElement('tr');
        emptyRow.innerHTML = `
            <td colspan="6" class="empty-state">No brands match your search term: "${escapeHtml(searchTerm)}"</td>
        `;
        container.appendChild(emptyRow);
    } else if (visibleCount > 0 && existingEmpty) {
        existingEmpty.parentElement.remove();
    }
}

// Escape HTML to prevent XSS
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
