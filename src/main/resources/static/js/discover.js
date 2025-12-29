/**
 * Discover Page - Dashboard for coffee exploration
 * Uses static cache for page load, live Neo4j queries for drill-down
 */

// ==================== CONNECTED FILTER STATE ====================

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

// ==================== LEGACY DATA (for Tasting Notes Explorer) ====================

let discoverData = {
    origins: null,
    processes: null,
    varieties: null,
    hiddenGems: null,
    roastLevels: null
};

// Flavor Explorer state - 3-level hierarchy with additive note selection
let explorerState = {
    currentView: 'categories', // 'categories' | 'attributes' | 'notes' | 'products'
    currentCategory: null,
    currentAttribute: null,     // Selected attribute ID
    currentAttributeName: null, // Selected attribute display name
    selectedNotes: [],          // Array of selected tasting note IDs (AND logic)
    selectedNoteNames: [],      // Array of selected tasting note display names
    categories: null,
    attributes: [],             // Attributes for current category
    notes: [],                  // TastingNotes for current attribute
    products: []
};

// Static flavor explorer cache (loaded once)
let flavorExplorerCache = null;

document.addEventListener('DOMContentLoaded', () => {
    // Initialize connected filters first
    initConnectedFilters();

    // Then load tasting notes explorer
    loadFlavorCategories();

    setupEventListeners();
    handleUrlParams();
});

/**
 * Handle URL parameters for pre-selecting filters
 * Examples:
 *   /discover.html?flavor=chocolate
 *   /discover.html?origin=Ethiopia&process=Natural&roast=Light
 */
function handleUrlParams() {
    const urlParams = new URLSearchParams(window.location.search);

    // Handle flavor search (existing)
    const flavorParam = urlParams.get('flavor');
    if (flavorParam) {
        setTimeout(() => searchFlavorFromUrl(flavorParam), 300);
    }

    // Handle guide page filters (new)
    const originParam = urlParams.get('origin');
    const processParam = urlParams.get('process');
    const roastParam = urlParams.get('roast');

    if (originParam || processParam || roastParam) {
        // Set filters from URL params
        // Origin can be comma-separated for multiple origins
        if (originParam) filters.origin = originParam;
        if (processParam) filters.process = processParam;
        if (roastParam) filters.roast = roastParam;

        // Reload filters and products after page initializes
        setTimeout(async () => {
            await loadFilterOptions();
            await loadFilteredProducts();

            // Scroll to the connected filters section
            const filtersSection = document.getElementById('connected-filters');
            if (filtersSection) {
                filtersSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
        }, 200);
    }
}

/**
 * Search for a flavor from URL parameter - direct search to products
 */
async function searchFlavorFromUrl(flavorName) {
    const normalizedFlavor = flavorName.toLowerCase().trim();

    // Update state - this is a direct search, so treat as a TastingNote
    explorerState.currentView = 'products';
    explorerState.currentNote = normalizedFlavor;
    explorerState.currentNoteName = flavorName;

    // Update breadcrumb
    updateBreadcrumb([{ name: flavorName, type: 'note', id: normalizedFlavor }]);

    // Hide category grid, show products section
    const categoryGrid = document.getElementById('category-grid');
    const productsSection = document.getElementById('explorer-products');
    const productsList = document.getElementById('explorer-products-list');
    const productCount = document.getElementById('product-count');

    if (categoryGrid) categoryGrid.classList.add('hidden');
    if (productsSection) {
        productsSection.classList.remove('hidden');
        if (productsList) productsList.innerHTML = '<div class="loading-placeholder">Searching for "' + escapeHtml(flavorName) + '"...</div>';
    }

    try {
        // Fetch products for this TastingNote
        const response = await fetch(`/api/flavor-wheel/products?flavor=${encodeURIComponent(normalizedFlavor)}`);

        if (!response.ok) {
            throw new Error('Flavor not found');
        }

        const data = await response.json();
        explorerState.products = data.products || [];

        if (productCount) productCount.textContent = explorerState.products.length;

        if (explorerState.products.length > 0) {
            if (productsList) productsList.innerHTML = renderProductTable(explorerState.products);
        } else {
            if (productsList) productsList.innerHTML = `<div class="empty-state">No products found with flavor "${escapeHtml(flavorName)}"</div>`;
        }

    } catch (error) {
        console.error('Error searching flavor:', error);
        if (productsList) {
            productsList.innerHTML = `<div class="empty-state">No products found with flavor "${escapeHtml(flavorName)}"</div>`;
        }
    }
}

/**
 * Load all data - now only loads Tasting Notes Explorer
 * Connected filters are loaded separately via initConnectedFilters()
 */
async function loadAllData() {
    // This function is kept for backwards compatibility
    // but no longer needs to load the old widget caches
    // since we now use the connected filter API
}

/**
 * Fetch a cache file with error handling
 */
async function fetchCache(filename) {
    try {
        const response = await fetch(`/cache/${filename}`);
        if (!response.ok) {
            console.warn(`Cache file not found: ${filename}`);
            return null;
        }
        return await response.json();
    } catch (error) {
        console.warn(`Error loading ${filename}:`, error);
        return null;
    }
}

/**
 * Setup event listeners
 */
function setupEventListeners() {
    // Modal close on outside click
    const modal = document.getElementById('discover-modal');
    if (modal) {
        modal.addEventListener('click', (e) => {
            if (e.target === modal) closeModal();
        });
    }
}

// ==================== FLAVOR EXPLORER ====================

/**
 * Load flavor explorer cache from static JSON file
 * This eliminates Neo4j queries for the most common operations
 */
async function loadFlavorExplorerCache() {
    if (flavorExplorerCache) return flavorExplorerCache;

    try {
        const response = await fetch('/cache/discover-flavor-explorer.json');
        if (response.ok) {
            flavorExplorerCache = await response.json();
            console.log('Loaded flavor explorer cache:', Object.keys(flavorExplorerCache.productsByFlavor || {}).length, 'flavors cached');
            return flavorExplorerCache;
        }
    } catch (error) {
        console.warn('Flavor explorer cache not available, falling back to API:', error);
    }
    return null;
}

/**
 * Load flavor categories - from cache first, then API fallback
 */
async function loadFlavorCategories() {
    const container = document.getElementById('category-grid');
    if (!container) return;

    try {
        // Try cache first
        const cache = await loadFlavorExplorerCache();
        if (cache && cache.categories) {
            explorerState.categories = cache.categories;
            renderCategoryGrid(cache.categories);
            return;
        }

        // Fallback to API
        const response = await fetch('/api/discover/flavor-explorer/categories');
        if (!response.ok) throw new Error('Failed to load categories');
        const categories = await response.json();

        explorerState.categories = categories;
        renderCategoryGrid(categories);
    } catch (error) {
        console.error('Error loading categories:', error);
        container.innerHTML = '<div class="error-state">Failed to load flavor categories</div>';
    }
}

/**
 * Render the category grid (default view)
 */
function renderCategoryGrid(categories) {
    const container = document.getElementById('category-grid');
    if (!container) return;

    container.innerHTML = categories.map(cat => `
        <div class="category-box" onclick="selectCategory('${escapeHtml(cat.name)}')">
            <span class="category-emoji">${cat.emoji || '🔘'}</span>
            <span class="category-name">${escapeHtml(cat.name)}</span>
            <span class="category-count">${cat.productCount}</span>
        </div>
    `).join('');

    // Show category grid, hide exploration
    container.classList.remove('hidden');
    document.getElementById('flavor-exploration')?.classList.add('hidden');
    document.getElementById('explorer-products')?.classList.add('hidden');
}

/**
 * Select a category to explore - Level 1 → Level 2 (shows Attributes)
 */
async function selectCategory(categoryName) {
    explorerState.currentView = 'attributes';
    explorerState.currentCategory = categoryName;
    explorerState.currentAttribute = null;
    explorerState.currentNote = null;

    // Update breadcrumb
    updateBreadcrumb([{ name: categoryName, type: 'category' }]);

    // Show loading state
    const container = document.getElementById('category-grid');
    container.innerHTML = '<div class="loading-placeholder">Loading attributes...</div>';

    try {
        // Fetch Attributes for this category
        const response = await fetch(`/api/discover/flavor-explorer/category/${encodeURIComponent(categoryName)}/attributes`);
        if (!response.ok) throw new Error('Failed to load attributes');
        const data = await response.json();

        explorerState.attributes = data.attributes || [];
        renderCategoryAttributes(data);
    } catch (error) {
        console.error('Error loading category attributes:', error);
        container.innerHTML = `<div class="error-state">Failed to load: ${error.message}</div>`;
    }
}

/**
 * Render Attributes within a category (Level 2)
 */
function renderCategoryAttributes(data, showAll = false) {
    const container = document.getElementById('category-grid');
    if (!container) return;

    const INITIAL_LIMIT = 20;
    const attributes = data.attributes || [];
    let html = '';

    if (attributes.length > 0) {
        const attributesToShow = showAll ? attributes : attributes.slice(0, INITIAL_LIMIT);
        const hasMore = attributes.length > INITIAL_LIMIT;

        html = `
            <div class="flavor-chips">
                ${attributesToShow.map(a => `
                    <span class="flavor-chip" onclick="selectAttribute('${escapeHtml(a.id)}', '${escapeHtml(a.name)}')">
                        ${escapeHtml(a.name)} <span class="chip-count">${a.productCount}</span>
                    </span>
                `).join('')}
            </div>
        `;

        if (hasMore && !showAll) {
            html += `
                <div class="show-more-container">
                    <button class="btn-show-more" onclick="showAllAttributes()">
                        Show more (${attributes.length - INITIAL_LIMIT} more)
                    </button>
                </div>
            `;
        } else if (showAll && hasMore) {
            html += `
                <div class="show-more-container">
                    <button class="btn-show-more" onclick="showLessAttributes()">
                        Show less
                    </button>
                </div>
            `;
        }
    } else {
        html = '<div class="empty-state">No attributes found in this category</div>';
    }

    container.innerHTML = html;
}

/**
 * Show all attributes in current category
 */
function showAllAttributes() {
    if (explorerState.attributes && explorerState.attributes.length > 0) {
        renderCategoryAttributes({ attributes: explorerState.attributes }, true);
    }
}

/**
 * Show limited attributes in current category
 */
function showLessAttributes() {
    if (explorerState.attributes && explorerState.attributes.length > 0) {
        renderCategoryAttributes({ attributes: explorerState.attributes }, false);
    }
}

/**
 * Select an Attribute - Level 2 → Level 3 (shows TastingNotes)
 */
async function selectAttribute(attributeId, attributeName) {
    explorerState.currentView = 'notes';
    explorerState.currentAttribute = attributeId;
    explorerState.currentAttributeName = attributeName;
    explorerState.currentNote = null;

    // Update breadcrumb
    updateBreadcrumb([
        { name: explorerState.currentCategory, type: 'category' },
        { name: attributeName, type: 'attribute', id: attributeId }
    ]);

    // Show loading state
    const container = document.getElementById('category-grid');
    container.innerHTML = '<div class="loading-placeholder">Loading tasting notes...</div>';

    try {
        // Fetch TastingNotes for this Attribute
        const response = await fetch(`/api/discover/flavor-explorer/attribute/${encodeURIComponent(attributeId)}/notes`);
        if (!response.ok) throw new Error('Failed to load tasting notes');
        const data = await response.json();

        explorerState.notes = data.notes || [];
        renderAttributeNotes(data);
    } catch (error) {
        console.error('Error loading attribute notes:', error);
        container.innerHTML = `<div class="error-state">Failed to load: ${error.message}</div>`;
    }
}

/**
 * Render TastingNotes for an Attribute (Level 3)
 */
function renderAttributeNotes(data, showAll = false) {
    const container = document.getElementById('category-grid');
    if (!container) return;

    const INITIAL_LIMIT = 20;
    const notes = data.notes || [];
    let html = '';

    if (notes.length > 0) {
        const notesToShow = showAll ? notes : notes.slice(0, INITIAL_LIMIT);
        const hasMore = notes.length > INITIAL_LIMIT;

        html = `
            <div class="flavor-chips">
                ${notesToShow.map(n => `
                    <span class="flavor-chip note-chip" onclick="selectNote('${escapeHtml(n.id)}', '${escapeHtml(n.name)}')">
                        ${escapeHtml(n.name)} <span class="chip-count">${n.productCount}</span>
                    </span>
                `).join('')}
            </div>
        `;

        if (hasMore && !showAll) {
            html += `
                <div class="show-more-container">
                    <button class="btn-show-more" onclick="showAllNotes()">
                        Show more (${notes.length - INITIAL_LIMIT} more)
                    </button>
                </div>
            `;
        } else if (showAll && hasMore) {
            html += `
                <div class="show-more-container">
                    <button class="btn-show-more" onclick="showLessNotes()">
                        Show less
                    </button>
                </div>
            `;
        }
    } else {
        html = '<div class="empty-state">No specific tasting notes found for this attribute</div>';
    }

    container.innerHTML = html;
}

/**
 * Show all notes for current attribute
 */
function showAllNotes() {
    if (explorerState.notes && explorerState.notes.length > 0) {
        renderAttributeNotes({ notes: explorerState.notes }, true);
    }
}

/**
 * Show limited notes for current attribute
 */
function showLessNotes() {
    if (explorerState.notes && explorerState.notes.length > 0) {
        renderAttributeNotes({ notes: explorerState.notes }, false);
    }
}

/**
 * Select a TastingNote - Level 3 → Level 4 (shows Products + Correlated Notes)
 * This is the first note selection (resets any previous selections)
 */
async function selectNote(noteId, noteName) {
    // Reset to single note selection
    explorerState.selectedNotes = [noteId];
    explorerState.selectedNoteNames = [noteName];
    explorerState.currentView = 'products';

    await loadProductsWithCorrelations();
}

/**
 * Add a correlated note to narrow down results (AND logic)
 */
async function addCorrelatedNote(noteId, noteName) {
    // Prevent adding the same note twice
    if (explorerState.selectedNotes.includes(noteId)) {
        console.log('Note already selected:', noteName);
        return;
    }

    // Add to selection
    explorerState.selectedNotes.push(noteId);
    explorerState.selectedNoteNames.push(noteName);

    await loadProductsWithCorrelations();
}

/**
 * Remove a note from the selection (click on breadcrumb)
 */
async function removeNoteFromPath(index) {
    // Keep notes up to and including the clicked index
    explorerState.selectedNotes = explorerState.selectedNotes.slice(0, index + 1);
    explorerState.selectedNoteNames = explorerState.selectedNoteNames.slice(0, index + 1);

    if (explorerState.selectedNotes.length === 0) {
        // Go back to attribute level
        backToAttribute(explorerState.currentAttribute, explorerState.currentAttributeName);
    } else {
        await loadProductsWithCorrelations();
    }
}

/**
 * Load products matching ALL selected notes (AND logic) + show correlations
 */
async function loadProductsWithCorrelations() {
    // Build breadcrumb
    const breadcrumbItems = [];
    if (explorerState.currentCategory) {
        breadcrumbItems.push({ name: explorerState.currentCategory, type: 'category' });
    }
    if (explorerState.currentAttributeName) {
        breadcrumbItems.push({ name: explorerState.currentAttributeName, type: 'attribute', id: explorerState.currentAttribute });
    }
    // Add all selected notes to breadcrumb
    explorerState.selectedNoteNames.forEach((name, i) => {
        breadcrumbItems.push({ name: name, type: 'note', id: explorerState.selectedNotes[i], index: i });
    });
    updateBreadcrumb(breadcrumbItems);

    // Show loading in products section
    const categoryGrid = document.getElementById('category-grid');
    const productsSection = document.getElementById('explorer-products');
    const productsList = document.getElementById('explorer-products-list');
    const productCount = document.getElementById('product-count');

    if (categoryGrid) categoryGrid.classList.add('hidden');
    if (productsSection) {
        productsSection.classList.remove('hidden');
        if (productsList) productsList.innerHTML = '<div class="loading-placeholder">Loading products...</div>';
    }

    try {
        // Use multi-flavor search with AND logic
        const flavorsParam = explorerState.selectedNotes.join(',');
        const productsResponse = await fetch(`/api/flavor-wheel/search?flavors=${encodeURIComponent(flavorsParam)}&matchAll=true`);

        if (!productsResponse.ok) throw new Error('Failed to load products');
        const productsData = await productsResponse.json();
        explorerState.products = productsData.products || [];

        // Get correlations for the LAST selected note (to show what can be added next)
        const lastNote = explorerState.selectedNotes[explorerState.selectedNotes.length - 1];
        let correlations = [];
        try {
            const correlationsResponse = await fetch(`/api/flavor-wheel/correlations?flavor=${encodeURIComponent(lastNote)}`);
            if (correlationsResponse.ok) {
                const correlationsData = await correlationsResponse.json();
                correlations = correlationsData.correlations || [];
            }
        } catch (e) {
            console.warn('Could not fetch correlations:', e);
        }

        // Filter out already selected notes from correlations
        correlations = correlations.filter(c =>
            !explorerState.selectedNotes.includes(c.flavor.toLowerCase())
        );

        if (productCount) productCount.textContent = explorerState.products.length;

        // Render correlated notes + products
        if (productsList) {
            let html = '';

            // Show current filter path
            if (explorerState.selectedNotes.length > 1) {
                html += `
                    <div class="filter-path-info">
                        <strong>Filtering by:</strong> ${explorerState.selectedNoteNames.map(n => `"${escapeHtml(n)}"`).join(' + ')}
                    </div>
                `;
            }

            // Show correlated notes for further narrowing (only if products > 1)
            if (correlations.length > 0 && explorerState.products.length > 1) {
                html += `
                    <div class="correlated-notes-section">
                        <h4>🔗 Narrow down further <span class="hint">(click to add filter)</span></h4>
                        <div class="flavor-chips correlated-chips">
                            ${correlations.slice(0, 12).map(c => `
                                <span class="flavor-chip correlated" onclick="addCorrelatedNote('${escapeHtml(c.flavor.toLowerCase())}', '${escapeHtml(c.flavor)}')">
                                    ${escapeHtml(c.flavor)} <span class="chip-count">${c.percentage}%</span>
                                </span>
                            `).join('')}
                        </div>
                    </div>
                `;
            } else if (explorerState.products.length <= 1) {
                html += `
                    <div class="drill-complete-info">
                        ✅ Drill-down complete! ${explorerState.products.length === 1 ? 'Found the perfect match.' : 'No products match all filters.'}
                    </div>
                `;
            }

            // Render products table
            html += renderProductTable(explorerState.products);
            productsList.innerHTML = html;
        }
    } catch (error) {
        console.error('Error loading products:', error);
        if (productsList) productsList.innerHTML = `<div class="error-state">Failed to load: ${error.message}</div>`;
    }
}

/**
 * Update the breadcrumb navigation for 3-level hierarchy with multiple notes
 */
function updateBreadcrumb(items) {
    const breadcrumb = document.getElementById('flavor-breadcrumb');
    if (!breadcrumb) return;

    let html = '<span class="breadcrumb-item" onclick="clearExplorer()">Categories</span>';

    items.forEach((item, index) => {
        html += '<span class="breadcrumb-arrow">→</span>';

        const isLast = index === items.length - 1;

        if (item.type === 'category') {
            html += `<span class="breadcrumb-item breadcrumb-category ${isLast ? 'active' : ''}" onclick="backToCategory()">${escapeHtml(item.name)}</span>`;
        } else if (item.type === 'attribute') {
            html += `<span class="breadcrumb-item breadcrumb-attribute ${isLast ? 'active' : ''}" onclick="backToAttribute('${escapeHtml(item.id)}', '${escapeHtml(item.name)}')">${escapeHtml(item.name)}</span>`;
        } else if (item.type === 'note') {
            // Notes are clickable to trim the path back to that point
            html += `<span class="breadcrumb-item breadcrumb-note ${isLast ? 'active' : ''}" onclick="removeNoteFromPath(${item.index})">${escapeHtml(item.name)}</span>`;
        }
    });

    breadcrumb.innerHTML = html;
}

/**
 * Clear the explorer and go back to categories
 */
function clearExplorer() {
    explorerState.currentView = 'categories';
    explorerState.currentCategory = null;
    explorerState.currentAttribute = null;
    explorerState.currentAttributeName = null;
    explorerState.selectedNotes = [];
    explorerState.selectedNoteNames = [];
    explorerState.attributes = [];
    explorerState.notes = [];
    explorerState.products = [];

    // Reset breadcrumb
    const breadcrumb = document.getElementById('flavor-breadcrumb');
    if (breadcrumb) {
        breadcrumb.innerHTML = '<span class="breadcrumb-item active" onclick="clearExplorer()">Categories</span>';
    }

    // Render categories
    if (explorerState.categories) {
        renderCategoryGrid(explorerState.categories);
    } else {
        loadFlavorCategories();
    }

    // Hide products section, show category grid
    document.getElementById('explorer-products')?.classList.add('hidden');
    document.getElementById('category-grid')?.classList.remove('hidden');
}

/**
 * Go back to category view (shows Attributes)
 */
function backToCategory() {
    if (!explorerState.currentCategory) {
        clearExplorer();
        return;
    }

    explorerState.currentView = 'attributes';
    explorerState.currentAttribute = null;
    explorerState.currentAttributeName = null;
    explorerState.selectedNotes = [];
    explorerState.selectedNoteNames = [];
    explorerState.notes = [];
    explorerState.products = [];

    // Update breadcrumb
    updateBreadcrumb([{ name: explorerState.currentCategory, type: 'category' }]);

    // Render category attributes
    if (explorerState.attributes && explorerState.attributes.length > 0) {
        renderCategoryAttributes({ attributes: explorerState.attributes });
    } else {
        selectCategory(explorerState.currentCategory);
    }

    // Hide products, show category grid
    document.getElementById('explorer-products')?.classList.add('hidden');
    document.getElementById('category-grid')?.classList.remove('hidden');
}

/**
 * Go back to attribute view (shows TastingNotes)
 */
function backToAttribute(attributeId, attributeName) {
    if (!explorerState.currentCategory || !attributeId) {
        backToCategory();
        return;
    }

    explorerState.currentView = 'notes';
    explorerState.currentAttribute = attributeId;
    explorerState.currentAttributeName = attributeName;
    explorerState.selectedNotes = [];
    explorerState.selectedNoteNames = [];
    explorerState.products = [];

    // Update breadcrumb
    updateBreadcrumb([
        { name: explorerState.currentCategory, type: 'category' },
        { name: attributeName, type: 'attribute', id: attributeId }
    ]);

    // Render notes
    if (explorerState.notes && explorerState.notes.length > 0) {
        renderAttributeNotes({ notes: explorerState.notes });
    } else {
        selectAttribute(attributeId, attributeName);
    }

    // Hide products, show category grid
    document.getElementById('explorer-products')?.classList.add('hidden');
    document.getElementById('category-grid')?.classList.remove('hidden');
}

/**
 * Load a random rare flavor (searches as a TastingNote)
 */
async function loadRandomRareFlavor() {
    const btn = document.getElementById('random-rare-btn');
    if (btn) btn.disabled = true;

    try {
        const response = await fetch('/api/discover/flavor-explorer/random-rare');
        if (!response.ok) throw new Error('Failed to load random flavor');
        const data = await response.json();

        if (data.error) {
            alert('No rare flavors available at the moment');
            return;
        }

        // Search for this flavor directly (goes straight to products)
        await searchFlavorFromUrl(data.name || data.id);

    } catch (error) {
        console.error('Error loading random flavor:', error);
        alert('Failed to load random flavor');
    } finally {
        if (btn) btn.disabled = false;
    }
}

// ==================== ORIGIN EXPLORER ====================

function renderOriginExplorer(data) {
    const container = document.getElementById('origin-boxes');
    if (!container || !data || !data.origins) {
        if (container) container.innerHTML = '<div class="empty-state">No origin data available</div>';
        return;
    }

    const origins = data.origins.slice(0, 12);
    const maxCount = Math.max(...origins.map(o => o.productCount));

    container.innerHTML = origins.map(origin => {
        const sizeClass = getBoxSize(origin.productCount, maxCount);
        return `
            <div class="trend-box origin-box ${sizeClass}" onclick="showOriginDetail('${escapeHtml(origin.country)}')">
                <div class="trend-box-name">${escapeHtml(origin.country)}</div>
                <div class="trend-box-count">${origin.productCount} products</div>
                <div class="origin-flavors">${origin.topFlavors ? origin.topFlavors.slice(0, 3).join(', ') : ''}</div>
            </div>
        `;
    }).join('');
}

async function showOriginDetail(country) {
    openModal(`${country} Coffee Profile`);
    const modalBody = document.getElementById('modal-body');
    modalBody.innerHTML = '<div class="loading-placeholder">Loading products...</div>';

    try {
        const response = await fetch(`/api/discover/origin/${encodeURIComponent(country)}/products`);
        if (!response.ok) throw new Error('Failed to load products');
        const products = await response.json();

        const originData = discoverData.origins?.origins?.find(o => o.country === country);

        modalBody.innerHTML = `
            <div class="origin-detail">
                ${originData ? `
                    <div class="origin-stats">
                        <div class="stat"><strong>Products:</strong> ${originData.productCount}</div>
                        <div class="stat"><strong>Avg Price:</strong> £${originData.avgPricePer100g?.toFixed(2) || 'N/A'}/100g</div>
                        <div class="stat"><strong>Top Processes:</strong> ${originData.topProcesses?.join(', ') || 'N/A'}</div>
                        <div class="stat"><strong>Top Regions:</strong> ${originData.topRegions?.join(', ') || 'N/A'}</div>
                    </div>
                ` : ''}
                <h4>Products from ${country}</h4>
                ${renderProductTable(products)}
            </div>
        `;
    } catch (error) {
        modalBody.innerHTML = `<div class="error-state">Failed to load products: ${error.message}</div>`;
    }
}

// ==================== PROCESS COMPARISON ====================

function renderProcessComparison(data) {
    const container = document.getElementById('process-comparison');
    if (!container || !data || !data.processes) {
        if (container) container.innerHTML = '<div class="empty-state">No process data available</div>';
        return;
    }

    container.innerHTML = data.processes.map(process => `
        <div class="trend-box process-box" onclick="showProcessProducts('${escapeHtml(process.name)}')">
            <div class="trend-box-name">${escapeHtml(process.name)}</div>
            <div class="trend-box-count">${process.productCount} products</div>
            <div class="box-flavors">${process.typicalFlavors?.slice(0, 4).join(', ') || ''}</div>
        </div>
    `).join('');
}

async function showProcessProducts(processName) {
    openModal(`${processName} Process Products`);
    const modalBody = document.getElementById('modal-body');
    modalBody.innerHTML = '<div class="loading-placeholder">Loading products...</div>';

    try {
        const response = await fetch(`/api/discover/process/${encodeURIComponent(processName)}/products`);
        if (!response.ok) throw new Error('Failed to load products');
        const products = await response.json();

        modalBody.innerHTML = renderProductTable(products);
    } catch (error) {
        modalBody.innerHTML = `<div class="error-state">Failed to load products: ${error.message}</div>`;
    }
}

// ==================== ROAST LEVEL ====================

function renderRoastLevels(data) {
    const container = document.getElementById('roast-levels');
    if (!container || !data || !data.roastLevels) {
        if (container) container.innerHTML = '<div class="empty-state">No roast level data available</div>';
        return;
    }

    // Define roast level colors and descriptions
    const roastInfo = {
        'Light': { color: '#d4a574', icon: '☀️', desc: 'Bright, acidic, fruity' },
        'Medium': { color: '#a0522d', icon: '🌤️', desc: 'Balanced, sweet, caramel' },
        'Medium-Dark': { color: '#8b4513', icon: '🌥️', desc: 'Rich, chocolatey' },
        'Dark': { color: '#4a2c2a', icon: '🌑', desc: 'Bold, smoky, bitter' },
        'Omni': { color: '#6b5b4f', icon: '🔄', desc: 'Versatile roast' }
    };

    container.innerHTML = data.roastLevels.map(roast => {
        const info = roastInfo[roast.name] || { color: '#8b7355', icon: '☕', desc: '' };
        return `
            <div class="trend-box roast-box" onclick="showRoastProducts('${escapeHtml(roast.name)}')"
                 style="border-left: 4px solid ${info.color}">
                <div class="trend-box-name">${info.icon} ${escapeHtml(roast.name)}</div>
                <div class="trend-box-count">${roast.productCount} products</div>
                <div class="box-flavors">${info.desc}</div>
            </div>
        `;
    }).join('');
}

async function showRoastProducts(roastLevel) {
    openModal(`${roastLevel} Roast Products`);
    const modalBody = document.getElementById('modal-body');
    modalBody.innerHTML = '<div class="loading-placeholder">Loading products...</div>';

    try {
        const response = await fetch(`/api/discover/roast/${encodeURIComponent(roastLevel)}/products`);
        if (!response.ok) throw new Error('Failed to load products');
        const products = await response.json();

        modalBody.innerHTML = renderProductTable(products);
    } catch (error) {
        modalBody.innerHTML = `<div class="error-state">Failed to load products: ${error.message}</div>`;
    }
}

// ==================== VARIETY SPOTLIGHT ====================

function renderVarietySpotlight(data) {
    const container = document.getElementById('variety-spotlight');
    if (!container || !data || !data.varieties) {
        if (container) container.innerHTML = '<div class="empty-state">No variety data available</div>';
        return;
    }

    container.innerHTML = data.varieties.slice(0, 6).map(variety => `
        <div class="trend-box variety-box" onclick="showVarietyProducts('${escapeHtml(variety.name)}')">
            <div class="trend-box-name">${escapeHtml(variety.name)}</div>
            <div class="trend-box-count">${variety.productCount} products</div>
            <div class="box-flavors">${variety.typicalFlavors?.slice(0, 3).join(', ') || ''}</div>
            <div class="box-meta">${variety.origins?.slice(0, 3).join(', ') || ''}</div>
        </div>
    `).join('');
}

async function showVarietyProducts(varietyName) {
    openModal(`${varietyName} Variety Products`);
    const modalBody = document.getElementById('modal-body');
    modalBody.innerHTML = '<div class="loading-placeholder">Loading products...</div>';

    try {
        const response = await fetch(`/api/discover/variety/${encodeURIComponent(varietyName)}/products`);
        if (!response.ok) throw new Error('Failed to load products');
        const products = await response.json();

        modalBody.innerHTML = renderProductTable(products);
    } catch (error) {
        modalBody.innerHTML = `<div class="error-state">Failed to load products: ${error.message}</div>`;
    }
}

// ==================== HIDDEN GEMS ====================

function renderHiddenGems(data) {
    const container = document.getElementById('hidden-gems');
    if (!container || !data || !data.gems) {
        if (container) container.innerHTML = '<div class="empty-state">No hidden gems found</div>';
        return;
    }

    container.innerHTML = data.gems.slice(0, 5).map(gem => `
        <div class="trend-box gem-box" onclick="showGemProducts('${gem.type}', '${escapeHtml(gem.name)}')">
            <div class="trend-box-name">${gem.type === 'origin' ? '🌍 ' : '🏢 '}${escapeHtml(gem.name)}</div>
            <div class="trend-box-count">${gem.productCount} products • £${gem.avgPrice?.toFixed(2) || 'N/A'}/100g</div>
            <div class="box-reason">${escapeHtml(gem.reason || '')}</div>
        </div>
    `).join('');
}

async function showGemProducts(type, name) {
    openModal(`${name} Products`);
    const modalBody = document.getElementById('modal-body');
    modalBody.innerHTML = '<div class="loading-placeholder">Loading products...</div>';

    try {
        const endpoint = type === 'origin'
            ? `/api/discover/origin/${encodeURIComponent(name)}/products`
            : `/api/products/search?query=${encodeURIComponent(name)}&limit=20`;

        const response = await fetch(endpoint);
        if (!response.ok) throw new Error('Failed to load products');
        const products = await response.json();

        modalBody.innerHTML = renderProductTable(products);
    } catch (error) {
        modalBody.innerHTML = `<div class="error-state">Failed to load products: ${error.message}</div>`;
    }
}

// ==================== UTILITY FUNCTIONS ====================

function renderProductTable(products) {
    if (!products || products.length === 0) {
        return '<div class="empty-state">No products found</div>';
    }

    return `
        <table class="products-table">
            <thead>
                <tr>
                    <th>Product</th>
                    <th>Brand</th>
                    <th>Origin</th>
                    <th>Roast</th>
                    <th>Price</th>
                </tr>
            </thead>
            <tbody>
                ${products.slice(0, 20).map(p => `
                    <tr>
                        <td><a href="/product-detail.html?id=${p.productId || p.id}">${escapeHtml(p.productName || p.name || 'Unknown')}</a></td>
                        <td>${escapeHtml(getProductBrand(p))}</td>
                        <td>${escapeHtml(getProductOrigin(p))}</td>
                        <td>${escapeHtml(getProductRoast(p))}</td>
                        <td>${p.price ? `£${parseFloat(p.price).toFixed(2)}` : ''}</td>
                    </tr>
                `).join('')}
            </tbody>
        </table>
    `;
}

/**
 * Extract brand name from product (handles different data structures)
 */
function getProductBrand(p) {
    // Neo4j ProductNode structure: soldBy.name
    if (p.soldBy?.name) return p.soldBy.name;
    // PostgreSQL/DTO structure: brandName or brand.name
    if (p.brandName) return p.brandName;
    if (p.brand?.name) return p.brand.name;
    return '';
}

/**
 * Extract origin from product (handles different data structures)
 */
function getProductOrigin(p) {
    // Neo4j ProductNode structure: origins (Set<OriginNode>)
    if (p.origins && p.origins.length > 0) {
        return p.origins.map(o => o.country || o.name).filter(Boolean).join(', ');
    }
    // Array format
    if (Array.isArray(p.origins) && p.origins.length > 0) {
        return p.origins.map(o => o.country || o).filter(Boolean).join(', ');
    }
    // PostgreSQL/DTO structure: origin string
    if (p.origin) return p.origin;
    return '';
}

/**
 * Extract roast level from product (handles different data structures)
 */
function getProductRoast(p) {
    // Neo4j ProductNode structure: roastLevel.level
    if (p.roastLevel?.level) return p.roastLevel.level;
    // String format
    if (typeof p.roastLevel === 'string') return p.roastLevel;
    return '';
}

function getBoxSize(count, maxCount) {
    const ratio = count / maxCount;
    if (ratio >= 0.8) return 'box-xl';
    if (ratio >= 0.5) return 'box-lg';
    if (ratio >= 0.3) return 'box-md';
    return 'box-sm';
}

function openModal(title) {
    const modal = document.getElementById('discover-modal');
    const modalTitle = document.getElementById('modal-title');
    if (modal) modal.classList.remove('hidden');
    if (modalTitle) modalTitle.textContent = title;
}

function closeModal() {
    const modal = document.getElementById('discover-modal');
    if (modal) modal.classList.add('hidden');
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ==================== CONNECTED FILTERS ====================

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

    filterOptions.processes.slice(0, 8).forEach(p => {
        const isSelected = filters.process === p.name;
        html += `<span class="filter-chip ${isSelected ? 'selected' : ''}" data-value="${escapeHtml(p.name)}" onclick="selectFilter('process', '${escapeHtml(p.name)}')">
            ${escapeHtml(p.name)} <span class="chip-count">${p.count}</span>
        </span>`;
    });

    container.innerHTML = html;
}

/**
 * Render origin filter list (LEFT sidebar)
 * Supports multiple selected origins (comma-separated)
 */
function renderOriginFilters() {
    const container = document.getElementById('origin-filters');
    if (!container) return;

    // Parse selected origins (comma-separated)
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
 * Toggle origin filter (add/remove from comma-separated list)
 */
async function toggleOriginFilter(originName) {
    // Parse current origins
    const selectedOrigins = filters.origin
        ? filters.origin.split(',').map(o => o.trim()).filter(o => o)
        : [];

    const index = selectedOrigins.findIndex(o => o.toLowerCase() === originName.toLowerCase());

    if (index > -1) {
        // Remove origin
        selectedOrigins.splice(index, 1);
    } else {
        // Add origin
        selectedOrigins.push(originName);
    }

    // Update filter
    filters.origin = selectedOrigins.length > 0 ? selectedOrigins.join(',') : null;

    // Reset pagination and reload
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
 * Select a filter value - independent filters (no cascade reset)
 * All filters work together, counts update based on current selections
 */
async function selectFilter(dimension, value) {
    filters[dimension] = value;

    // Reset pagination
    currentPage = 0;

    // Reload options and products
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

    // Show loading state on first page
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

        // Show/hide load more button
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
        // Parse if string
        const arr = typeof notes === 'string' ? JSON.parse(notes) : notes;
        if (Array.isArray(arr)) {
            return arr.slice(0, 3).map(n => escapeHtml(n)).join(', ');
        }
    } catch (e) {
        // If string, just show as-is
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
        // Display multiple origins nicely
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
