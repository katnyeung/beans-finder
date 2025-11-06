// API Base URL
const API_BASE = '/api';

// Store brands data for sorting
let brandsData = [];
let currentSort = { column: null, ascending: true };

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
            <td colspan="5" class="empty-state">No brands match your search term: "${escapeHtml(searchTerm)}"</td>
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
