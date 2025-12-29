/**
 * Filter Products Page - Filter coffee by Process, Origin, Variety, Roast
 */

// Filter state
let filters = {
    process: null,
    origin: null,
    variety: null,
    roast: null
};

let filterOptions = {
    processes: [],
    origins: [],
    varieties: [],
    roasts: []
};

let filteredProducts = [];
let currentPage = 0;
let totalProducts = 0;
let pageSize = 20;

document.addEventListener('DOMContentLoaded', () => {
    handleUrlParams();
    initConnectedFilters();
});

/**
 * Handle URL parameters for pre-selecting filters
 */
function handleUrlParams() {
    const urlParams = new URLSearchParams(window.location.search);

    const originParam = urlParams.get('origin');
    const processParam = urlParams.get('process');
    const roastParam = urlParams.get('roast');
    const varietyParam = urlParams.get('variety');

    if (originParam) filters.origin = originParam;
    if (processParam) filters.process = processParam;
    if (roastParam) filters.roast = roastParam;
    if (varietyParam) filters.variety = varietyParam;
}

/**
 * Initialize connected filters
 */
async function initConnectedFilters() {
    await loadFilterOptions();
    await loadFilteredProducts();
}

/**
 * Load filter options from API
 */
async function loadFilterOptions() {
    try {
        const params = new URLSearchParams();
        if (filters.process) params.append('process', filters.process);
        if (filters.origin) params.append('origin', filters.origin);
        if (filters.variety) params.append('variety', filters.variety);
        if (filters.roast) params.append('roast', filters.roast);

        const response = await fetch(`/api/discover/filter-options?${params.toString()}`);
        if (!response.ok) throw new Error('Failed to load filter options');

        const data = await response.json();
        filterOptions = {
            processes: data.processes || [],
            origins: data.origins || [],
            varieties: data.varieties || [],
            roasts: data.roasts || []
        };
        totalProducts = data.totalProducts || 0;

        renderFilterOptions();
        updateResultsCount();
    } catch (error) {
        console.error('Error loading filter options:', error);
    }
}

/**
 * Render all filter options
 */
function renderFilterOptions() {
    renderProcessFilters();
    renderOriginFilters();
    renderVarietyFilters();
    renderRoastFilters();
}

/**
 * Render process filter chips (TOP)
 */
function renderProcessFilters() {
    const container = document.getElementById('process-filters');
    if (!container) return;

    let html = `<span class="filter-chip ${!filters.process ? 'selected' : ''}" data-value="" onclick="selectFilter('process', null)">All</span>`;

    filterOptions.processes.slice(0, 15).forEach(p => {
        const isSelected = filters.process === p.name;
        html += `<span class="filter-chip ${isSelected ? 'selected' : ''}" data-value="${escapeHtml(p.name)}" onclick="selectFilter('process', '${escapeHtml(p.name)}')">
            ${escapeHtml(p.name)} <span class="chip-count">${p.count}</span>
        </span>`;
    });

    container.innerHTML = html;
}

/**
 * Render origin filter list (LEFT sidebar)
 */
function renderOriginFilters() {
    const container = document.getElementById('origin-filters');
    if (!container) return;

    const selectedOrigins = filters.origin
        ? filters.origin.split(',').map(o => o.trim().toLowerCase())
        : [];

    let html = `<div class="filter-item ${selectedOrigins.length === 0 ? 'selected' : ''}" data-value="" onclick="selectFilter('origin', null)">All</div>`;

    filterOptions.origins.slice(0, 15).forEach(o => {
        const isSelected = selectedOrigins.includes(o.name.toLowerCase());
        const isDisabled = o.count === 0;
        html += `<div class="filter-item ${isSelected ? 'selected' : ''} ${isDisabled ? 'disabled' : ''}"
                     data-value="${escapeHtml(o.name)}"
                     ${isDisabled ? '' : `onclick="toggleOriginFilter('${escapeHtml(o.name)}')"`}>
            ${escapeHtml(o.name)} <span class="item-count">${o.count}</span>
        </div>`;
    });

    container.innerHTML = html;
}

/**
 * Toggle origin filter (multi-select)
 */
async function toggleOriginFilter(originName) {
    const selectedOrigins = filters.origin
        ? filters.origin.split(',').map(o => o.trim()).filter(o => o)
        : [];

    const index = selectedOrigins.findIndex(o => o.toLowerCase() === originName.toLowerCase());

    if (index > -1) {
        selectedOrigins.splice(index, 1);
    } else {
        selectedOrigins.push(originName);
    }

    filters.origin = selectedOrigins.length > 0 ? selectedOrigins.join(',') : null;

    currentPage = 0;
    await loadFilterOptions();
    await loadFilteredProducts();
}

/**
 * Render variety filter list (RIGHT sidebar)
 */
function renderVarietyFilters() {
    const container = document.getElementById('variety-filters');
    if (!container) return;

    let html = `<div class="filter-item ${!filters.variety ? 'selected' : ''}" data-value="" onclick="selectFilter('variety', null)">All</div>`;

    filterOptions.varieties.slice(0, 15).forEach(v => {
        const isSelected = filters.variety === v.name;
        const isDisabled = v.count === 0;
        html += `<div class="filter-item ${isSelected ? 'selected' : ''} ${isDisabled ? 'disabled' : ''}"
                     data-value="${escapeHtml(v.name)}"
                     ${isDisabled ? '' : `onclick="selectFilter('variety', '${escapeHtml(v.name)}')"`}>
            ${escapeHtml(v.name)} <span class="item-count">${v.count}</span>
        </div>`;
    });

    container.innerHTML = html;
}

/**
 * Render roast filter chips (BOTTOM)
 */
function renderRoastFilters() {
    const container = document.getElementById('roast-filters');
    if (!container) return;

    const roastIcons = {
        'Light': '☀️',
        'Medium': '🌤️',
        'Medium-Dark': '🌥️',
        'Dark': '🌑',
        'Omni': '🔄'
    };

    let html = `<span class="filter-chip ${!filters.roast ? 'selected' : ''}" data-value="" onclick="selectFilter('roast', null)">All</span>`;

    filterOptions.roasts.forEach(r => {
        const isSelected = filters.roast === r.name;
        const isDisabled = r.count === 0;
        const icon = roastIcons[r.name] || '☕';
        html += `<span class="filter-chip ${isSelected ? 'selected' : ''} ${isDisabled ? 'disabled' : ''}"
                       data-value="${escapeHtml(r.name)}"
                       ${isDisabled ? '' : `onclick="selectFilter('roast', '${escapeHtml(r.name)}')"`}>
            ${icon} ${escapeHtml(r.name)} <span class="chip-count">${r.count}</span>
        </span>`;
    });

    container.innerHTML = html;
}

/**
 * Select a filter value
 */
async function selectFilter(dimension, value) {
    filters[dimension] = value;
    currentPage = 0;

    await loadFilterOptions();
    await loadFilteredProducts();
}

/**
 * Clear all filters
 */
async function clearAllFilters() {
    filters = {
        process: null,
        origin: null,
        variety: null,
        roast: null
    };
    currentPage = 0;

    await loadFilterOptions();
    await loadFilteredProducts();
}

/**
 * Load filtered products from API
 */
async function loadFilteredProducts() {
    const resultsBody = document.getElementById('results-body');
    const loadMoreContainer = document.getElementById('load-more-container');

    if (!resultsBody) return;

    if (currentPage === 0) {
        resultsBody.innerHTML = '<tr><td colspan="4" class="loading-cell">Loading...</td></tr>';
    }

    try {
        const params = new URLSearchParams();
        if (filters.process) params.append('process', filters.process);
        if (filters.origin) params.append('origin', filters.origin);
        if (filters.variety) params.append('variety', filters.variety);
        if (filters.roast) params.append('roast', filters.roast);
        params.append('page', currentPage);
        params.append('size', pageSize);

        const response = await fetch(`/api/discover/filter-products?${params.toString()}`);
        if (!response.ok) throw new Error('Failed to load products');

        const data = await response.json();
        totalProducts = data.totalCount || 0;

        if (currentPage === 0) {
            filteredProducts = data.products || [];
        } else {
            filteredProducts = filteredProducts.concat(data.products || []);
        }

        renderProductResults();
        updateResultsCount();

        if (loadMoreContainer) {
            const hasMore = filteredProducts.length < totalProducts;
            loadMoreContainer.classList.toggle('hidden', !hasMore);
        }
    } catch (error) {
        console.error('Error loading products:', error);
        resultsBody.innerHTML = '<tr><td colspan="4" class="error-cell">Failed to load products</td></tr>';
    }
}

/**
 * Load more products (pagination)
 */
async function loadMoreProducts() {
    currentPage++;
    await loadFilteredProducts();
}

/**
 * Render product results table
 */
function renderProductResults() {
    const resultsBody = document.getElementById('results-body');
    if (!resultsBody) return;

    if (filteredProducts.length === 0) {
        resultsBody.innerHTML = '<tr><td colspan="4" class="empty-cell">No products match your filters</td></tr>';
        return;
    }

    resultsBody.innerHTML = filteredProducts.map(p => `
        <tr onclick="window.location.href='/product-detail.html?id=${p.id}'" style="cursor: pointer;">
            <td>
                <div class="product-cell">
                    <span class="product-name">${escapeHtml(p.productName || 'Unknown')}</span>
                    ${p.tastingNotes ? `<span class="product-notes">${formatTastingNotes(p.tastingNotes)}</span>` : ''}
                </div>
            </td>
            <td>${escapeHtml(p.brandName || '')}</td>
            <td>${escapeHtml(p.origin || '')}</td>
            <td>${p.price ? `£${parseFloat(p.price).toFixed(2)}` : ''}</td>
        </tr>
    `).join('');
}

/**
 * Format tasting notes for display
 */
function formatTastingNotes(notes) {
    if (!notes) return '';
    try {
        const arr = typeof notes === 'string' ? JSON.parse(notes) : notes;
        if (Array.isArray(arr)) {
            return arr.slice(0, 3).map(n => escapeHtml(n)).join(', ');
        }
    } catch (e) {
        if (typeof notes === 'string') {
            return escapeHtml(notes.substring(0, 50));
        }
    }
    return '';
}

/**
 * Update results count display
 */
function updateResultsCount() {
    const countEl = document.getElementById('results-count');
    if (!countEl) return;

    const activeFilters = [];
    if (filters.process) activeFilters.push(filters.process);
    if (filters.origin) {
        const origins = filters.origin.split(',').map(o => o.trim()).filter(o => o);
        if (origins.length > 1) {
            activeFilters.push(origins.join(' + '));
        } else {
            activeFilters.push(origins[0]);
        }
    }
    if (filters.variety) activeFilters.push(filters.variety);
    if (filters.roast) activeFilters.push(filters.roast);

    if (activeFilters.length > 0) {
        countEl.innerHTML = `<strong>${totalProducts}</strong> products: ${activeFilters.join(' • ')}`;
    } else {
        countEl.innerHTML = `<strong>${totalProducts}</strong> products`;
    }
}

/**
 * Escape HTML
 */
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
