// Flavor Wheel Grid Visualization (No D3.js)
const API_BASE = '/api/flavor-wheel';

// Color palette for SCA categories (coffee-themed)
const CATEGORY_COLORS = {
    'fruity': '#E74C3C',      // Red
    'floral': '#9B59B6',      // Purple
    'sweet': '#F39C12',       // Orange
    'nutty': '#8B5A3C',       // Brown
    'spices': '#E67E22',      // Dark Orange
    'roasted': '#34495E',     // Dark Gray
    'green': '#27AE60',       // Green
    'sour': '#F1C40F',        // Yellow
    'other': '#95A5A6'        // Gray
};

let flavorWheelData = null;
let currentProducts = [];
let hoveredFlavor = null;
let correlations = {};

// Initialize on page load
document.addEventListener('DOMContentLoaded', async () => {
    try {
        await loadFlavorWheelData();
        renderFlavorGrid();
        setupEventListeners();
    } catch (error) {
        showError('Failed to load flavor wheel data: ' + error.message);
    }
});

// Load flavor wheel hierarchy data
async function loadFlavorWheelData() {
    // Try static cache file first (no Neo4j queries!)
    let response = await fetch('/cache/flavor-wheel-data.json');

    if (!response.ok) {
        // Fallback to API if cache doesn't exist
        console.warn('Flavor wheel cache file not found, falling back to API');
        response = await fetch(`${API_BASE}/data`);

        if (!response.ok) {
            throw new Error('Failed to fetch flavor wheel data');
        }
    } else {
        console.log('Loaded flavor wheel data from cache (no Neo4j queries)');
    }

    flavorWheelData = await response.json();

    // Filter out any null or invalid categories/flavors
    if (flavorWheelData.categories) {
        flavorWheelData.categories = flavorWheelData.categories
            .filter(cat => cat && cat.name)
            .map(cat => ({
                ...cat,
                flavors: (cat.flavors || []).filter(f => f && f.name)
            }))
            .filter(cat => cat.flavors.length > 0);
    }

    console.log('Loaded flavor wheel data:', flavorWheelData);

    // Update stats
    document.getElementById('total-products').textContent = flavorWheelData.totalProducts || 0;
    document.getElementById('total-categories').textContent = flavorWheelData.totalCategories || 0;
    document.getElementById('total-flavors').textContent = flavorWheelData.totalFlavors || 0;

    // Hide loading, show wheel
    document.getElementById('loading').style.display = 'none';
    document.getElementById('wheel-section').style.display = 'block';
}

// Render the flavor grid with category zones (word cloud style)
function renderFlavorGrid() {
    const gridContainer = document.getElementById('flavor-grid');
    gridContainer.innerHTML = '';

    // Calculate max product count for sizing
    const maxCount = Math.max(...flavorWheelData.categories.map(cat => cat.productCount));

    // Calculate cell spans based on absolute count (better distribution)
    const calculateCellSpan = (count) => {
        if (count === 0) return 1;

        // Adjusted thresholds for better visual balance
        if (count >= 250) return 4;   // 250+ items: 4x4 cells (very large categories)
        if (count >= 30) return 3;    // 30-249 items: 3x3 cells (popular flavors)
        if (count >= 10) return 2;    // 10-29 items: 2x2 cells (common flavors)
        return 1; // <10 items: 1x1 cell (rare flavors)
    };

    // Define category zones (relative to center)
    // More popular categories closer to center within their zone
    const categoryZones = {
        'fruity': { direction: 'top', angle: 270 },
        'sweet': { direction: 'right', angle: 0 },
        'roasted': { direction: 'bottom-left', angle: 225 },
        'nutty': { direction: 'bottom', angle: 180 },
        'spices': { direction: 'top-right', angle: 315 },
        'floral': { direction: 'left', angle: 180 },
        'green': { direction: 'top-left', angle: 315 },
        'sour': { direction: 'bottom-right', angle: 135 },
        'other': { direction: 'center', angle: 0 }
    };

    // Group items by category with dynamic sizing
    const categoryGroups = {};

    flavorWheelData.categories.forEach(category => {
        const categoryItems = [];

        // Add category label itself
        const opacity = 0.3 + (category.productCount / maxCount) * 0.7;
        const span = calculateCellSpan(category.productCount);

        categoryItems.push({
            type: 'category',
            name: category.name,
            label: `${category.name.toUpperCase()}\n(${category.productCount})`,
            color: CATEGORY_COLORS[category.name] || '#95A5A6',
            opacity: opacity,
            productCount: category.productCount,
            span: span,
            onClick: () => selectCategory(category.name)
        });

        // Add flavors in this category (skip if flavor name matches category name to avoid duplicates)
        category.flavors.forEach(flavor => {
            // Skip if flavor name is the same as category name (e.g., "fruity" flavor in "fruity" category)
            if (flavor.name.toLowerCase() === category.name.toLowerCase()) {
                console.log(`Skipping duplicate: ${flavor.name} (matches category ${category.name})`);
                return;
            }

            const flavorOpacity = 0.3 + (flavor.productCount / maxCount) * 0.7;
            const flavorSpan = calculateCellSpan(flavor.productCount);

            categoryItems.push({
                type: 'flavor',
                name: flavor.name,
                category: category.name,
                label: `${capitalize(flavor.name)}\n(${flavor.productCount})`,
                color: CATEGORY_COLORS[category.name] || '#95A5A6',
                opacity: flavorOpacity,
                productCount: flavor.productCount,
                span: flavorSpan,
                onClick: () => handleFlavorClick(flavor.name, category.name)
            });
        });

        // Sort items within category by product count (descending)
        // Most popular will be placed closest to center
        categoryItems.sort((a, b) => (b.productCount || 0) - (a.productCount || 0));

        categoryGroups[category.name] = {
            items: categoryItems,
            zone: categoryZones[category.name] || categoryZones['other']
        };
    });

    // Layout algorithm: Pack items in zones
    const cellSize = 60; // Base cell size
    const gap = 4;

    // Estimate grid size needed
    let totalArea = 1; // Center cell
    Object.values(categoryGroups).forEach(group => {
        totalArea += group.items.reduce((sum, item) => sum + (item.span * item.span), 0);
    });
    const gridSize = Math.ceil(Math.sqrt(totalArea)) + 10; // Extra padding for zones

    gridContainer.style.display = 'grid';
    gridContainer.style.gridTemplateColumns = `repeat(${gridSize}, ${cellSize}px)`;
    gridContainer.style.gridTemplateRows = `repeat(${gridSize}, ${cellSize}px)`;
    gridContainer.style.gap = `${gap}px`;
    gridContainer.style.justifyContent = 'center';
    gridContainer.style.alignContent = 'center';

    // Create occupancy grid to track filled cells
    const occupied = Array(gridSize).fill(null).map(() => Array(gridSize).fill(false));

    // Place center "SCA Wheel" label
    const centerPos = Math.floor(gridSize / 2);
    const centerItem = {
        type: 'center',
        label: 'SCA Wheel',
        color: '#6B4423',
        opacity: 1,
        span: 1,
        onClick: null
    };
    placeItem(gridContainer, centerItem, centerPos, centerPos, occupied, cellSize);

    // Place category groups in their respective zones
    Object.entries(categoryGroups).forEach(([categoryName, group]) => {
        const zone = group.zone;
        const items = group.items;

        // Get positions for this zone, sorted by distance to center
        const zonePositions = getZonePositions(gridSize, centerPos, zone.direction);

        let itemIndex = 0;
        for (const pos of zonePositions) {
            if (itemIndex >= items.length) break;

            const item = items[itemIndex];

            // Check if this position can fit the item
            if (canPlaceItem(occupied, pos.row, pos.col, item.span, gridSize)) {
                placeItem(gridContainer, item, pos.row, pos.col, occupied, cellSize);
                itemIndex++;
            }
        }
    });
}

// Get positions for a specific zone, sorted by distance to center
function getZonePositions(gridSize, centerPos, direction) {
    const positions = [];

    // Define zone boundaries
    const zones = {
        'top': { rowStart: 0, rowEnd: centerPos, colStart: Math.floor(centerPos * 0.3), colEnd: Math.floor(centerPos * 1.7) },
        'bottom': { rowStart: centerPos + 1, rowEnd: gridSize, colStart: Math.floor(centerPos * 0.3), colEnd: Math.floor(centerPos * 1.7) },
        'left': { rowStart: Math.floor(centerPos * 0.3), rowEnd: Math.floor(centerPos * 1.7), colStart: 0, colEnd: centerPos },
        'right': { rowStart: Math.floor(centerPos * 0.3), rowEnd: Math.floor(centerPos * 1.7), colStart: centerPos + 1, colEnd: gridSize },
        'top-left': { rowStart: 0, rowEnd: centerPos, colStart: 0, colEnd: centerPos },
        'top-right': { rowStart: 0, rowEnd: centerPos, colStart: centerPos + 1, colEnd: gridSize },
        'bottom-left': { rowStart: centerPos + 1, rowEnd: gridSize, colStart: 0, colEnd: centerPos },
        'bottom-right': { rowStart: centerPos + 1, rowEnd: gridSize, colStart: centerPos + 1, colEnd: gridSize },
        'center': { rowStart: centerPos - 2, rowEnd: centerPos + 3, colStart: centerPos - 2, colEnd: centerPos + 3 }
    };

    const zone = zones[direction] || zones['center'];

    // Generate all positions in zone
    for (let row = zone.rowStart; row < zone.rowEnd; row++) {
        for (let col = zone.colStart; col < zone.colEnd; col++) {
            if (row >= 0 && row < gridSize && col >= 0 && col < gridSize) {
                // Calculate distance to center
                const distance = Math.sqrt(Math.pow(row - centerPos, 2) + Math.pow(col - centerPos, 2));
                positions.push({ row, col, distance });
            }
        }
    }

    // Sort by distance to center (ascending) - closer positions first
    positions.sort((a, b) => a.distance - b.distance);

    return positions;
}

// Check if an item can be placed at given position
function canPlaceItem(occupied, row, col, span, gridSize) {
    if (row + span > gridSize || col + span > gridSize) return false;

    for (let r = row; r < row + span; r++) {
        for (let c = col; c < col + span; c++) {
            if (occupied[r][c]) return false;
        }
    }
    return true;
}

// Place an item on the grid and mark occupied cells
function placeItem(container, item, row, col, occupied, cellSize) {
    const cellDiv = createCell(item, cellSize * item.span + (item.span - 1) * 4, cellSize * item.span + (item.span - 1) * 4);

    // Mark cells as occupied
    for (let r = row; r < row + item.span; r++) {
        for (let c = col; c < col + item.span; c++) {
            if (r < occupied.length && c < occupied[0].length) {
                occupied[r][c] = true;
            }
        }
    }

    // Position using grid coordinates (span across multiple cells)
    cellDiv.style.gridColumn = `${col + 1} / span ${item.span}`;
    cellDiv.style.gridRow = `${row + 1} / span ${item.span}`;

    container.appendChild(cellDiv);
}

// Generate spiral positions for cells (center outward)
function generateSpiralPositions(gridSize, startRow, startCol) {
    const positions = [];
    const centerRow = startRow || Math.floor(gridSize / 2);
    const centerCol = startCol || Math.floor(gridSize / 2);

    let x = centerCol, y = centerRow;
    let dx = 0, dy = -1;
    let steps = 1, stepCount = 0, directionChanges = 0;

    for (let i = 0; i < gridSize * gridSize; i++) {
        if (x >= 0 && x < gridSize && y >= 0 && y < gridSize) {
            positions.push({ row: y, col: x });
        }

        stepCount++;
        x += dx;
        y += dy;

        if (stepCount === steps) {
            stepCount = 0;
            const temp = dx;
            dx = -dy;
            dy = temp;
            directionChanges++;

            if (directionChanges % 2 === 0) {
                steps++;
            }
        }
    }

    return positions;
}

// Create a single cell element
function createCell(cell, width, height) {
    const cellDiv = document.createElement('div');
    cellDiv.className = `flavor-cell ${cell.type}-cell`;

    // Apply color and opacity
    cellDiv.style.backgroundColor = cell.color;
    cellDiv.style.opacity = cell.opacity;
    cellDiv.style.width = `${width}px`;
    cellDiv.style.height = `${height}px`;

    // Add label (handle multi-line)
    const label = document.createElement('span');
    label.className = 'cell-label';

    // Replace \n with <br> for multi-line labels
    const lines = cell.label.split('\n');
    lines.forEach((line, index) => {
        if (index > 0) {
            label.appendChild(document.createElement('br'));
        }
        label.appendChild(document.createTextNode(line));
    });

    // Font size based on cell type and span
    if (cell.type === 'center') {
        label.style.fontSize = '14px';
        label.style.fontWeight = 'bold';
    } else if (cell.type === 'category') {
        // Scale font based on span
        const fontSize = cell.span >= 4 ? '20px' : cell.span >= 3 ? '16px' : cell.span >= 2 ? '13px' : '11px';
        label.style.fontSize = fontSize;
        label.style.fontWeight = 'bold';
    } else {
        // Flavor cells
        const fontSize = cell.span >= 4 ? '16px' : cell.span >= 3 ? '14px' : cell.span >= 2 ? '11px' : '9px';
        label.style.fontSize = fontSize;
    }

    cellDiv.appendChild(label);

    // Store cell data
    cellDiv.dataset.cellName = cell.name || 'center';
    cellDiv.dataset.cellType = cell.type;
    if (cell.category) {
        cellDiv.dataset.cellCategory = cell.category; // Store which category this flavor belongs to
    }

    // Event listeners
    if (cell.onClick) {
        cellDiv.style.cursor = 'pointer';
        cellDiv.addEventListener('click', cell.onClick);
    }

    // Tooltip on hover
    if (cell.type === 'flavor') {
        cellDiv.title = `${cell.label.replace('\n', ' ')}\nClick to see correlations & products`;
    } else if (cell.type === 'category') {
        cellDiv.title = `${cell.label.replace('\n', ' ')}\nClick to see all products`;
    }

    return cellDiv;
}

// Handle flavor click - show correlations AND products
async function handleFlavorClick(flavorName, categoryName) {
    // Clear any existing correlation highlights
    clearCorrelationHighlights();

    // Load and show correlations
    await showFlavorCorrelations(flavorName);

    // Also show products in the panel
    await selectFlavor(flavorName, categoryName);
}

// Show flavor correlations (highlight correlated cells)
async function showFlavorCorrelations(flavorName) {
    hoveredFlavor = flavorName;

    // Load correlations if not cached
    if (!correlations[flavorName]) {
        try {
            const response = await fetch(`${API_BASE}/correlations?flavor=${encodeURIComponent(flavorName)}`);
            if (response.ok) {
                const data = await response.json();
                correlations[flavorName] = data.correlations || [];
            } else {
                correlations[flavorName] = [];
            }
        } catch (error) {
            console.error('Failed to load correlations:', error);
            correlations[flavorName] = [];
        }
    }

    // Highlight correlated cells AND the selected cell itself
    const correlated = correlations[flavorName];
    const cells = document.querySelectorAll('.flavor-cell');

    cells.forEach(cell => {
        const cellName = cell.dataset.cellName;
        const match = correlated.find(c => c.flavor === cellName);

        // Highlight if it's a correlated flavor
        if (match) {
            cell.classList.add('correlated');
            // Add correlation percentage to tooltip
            const percentage = match.percentage;
            const label = cell.querySelector('.cell-label');
            if (label) {
                cell.title = `${label.textContent}\n${percentage}% correlation with ${flavorName}`;
            }
        }

        // Also highlight the clicked flavor itself as "selected"
        if (cellName === flavorName) {
            cell.classList.add('selected');
            const label = cell.querySelector('.cell-label');
            if (label) {
                cell.title = `${label.textContent}\n(Selected - showing correlations)`;
            }
        }
    });
}

// Clear correlation highlights
function clearCorrelationHighlights() {
    hoveredFlavor = null;

    const cells = document.querySelectorAll('.flavor-cell');
    cells.forEach(cell => {
        cell.classList.remove('correlated');
        cell.classList.remove('selected');
        // Reset tooltip based on cell type
        const cellType = cell.dataset.cellType;
        const label = cell.querySelector('.cell-label');
        if (label) {
            const labelText = label.textContent;
            if (cellType === 'flavor') {
                cell.title = `${labelText}\nClick to see correlations & products`;
            } else if (cellType === 'category') {
                cell.title = `${labelText}\nClick to see all products`;
            }
        }
    });
}

// Select a category
async function selectCategory(categoryName) {
    // Clear previous highlights
    clearCorrelationHighlights();

    // Highlight the selected category AND all flavors in that category
    const cells = document.querySelectorAll('.flavor-cell');
    cells.forEach(cell => {
        const cellName = cell.dataset.cellName;
        const cellType = cell.dataset.cellType;
        const cellCategory = cell.dataset.cellCategory;

        // Highlight the category itself
        if (cellName === categoryName && cellType === 'category') {
            cell.classList.add('selected');
            const label = cell.querySelector('.cell-label');
            if (label) {
                cell.title = `${label.textContent}\n(Selected category - showing all flavors)`;
            }
        }

        // Highlight all flavors that belong to this category
        if (cellType === 'flavor' && cellCategory === categoryName) {
            cell.classList.add('correlated');
            const label = cell.querySelector('.cell-label');
            if (label) {
                cell.title = `${label.textContent}\n(Part of ${capitalize(categoryName)} family)`;
            }
        }
    });

    try {
        const response = await fetch(`${API_BASE}/products?category=${encodeURIComponent(categoryName)}`);
        if (!response.ok) throw new Error('Failed to fetch products');

        const data = await response.json();
        currentProducts = data.products || [];

        showProductsPanel(
            `Category: ${capitalize(categoryName)}`,
            `All products with ${categoryName} flavor notes`,
            currentProducts
        );
    } catch (error) {
        showError('Failed to load products: ' + error.message);
    }
}

// Select a flavor
async function selectFlavor(flavorName, categoryName) {
    try {
        const response = await fetch(`${API_BASE}/products?flavor=${encodeURIComponent(flavorName)}`);
        if (!response.ok) throw new Error('Failed to fetch products');

        const data = await response.json();
        currentProducts = data.products || [];

        showProductsPanel(
            `Flavor: ${capitalize(flavorName)}`,
            `Products with ${flavorName} tasting notes`,
            currentProducts
        );
    } catch (error) {
        showError('Failed to load products: ' + error.message);
    }
}

// Show products panel on the right
function showProductsPanel(title, description, products) {
    const panel = document.getElementById('products-panel');
    const panelTitle = document.getElementById('panel-title');
    const panelDescription = document.getElementById('panel-description');
    const productCount = document.getElementById('product-count');

    panelTitle.textContent = title;
    panelDescription.textContent = description;
    productCount.textContent = `${products.length} products`;

    renderProducts(products);

    panel.style.display = 'block';

    // Flash animation to indicate new products loaded
    panel.classList.add('flash-indicator');
    setTimeout(() => {
        panel.classList.remove('flash-indicator');
    }, 2000);

    // Add info message based on selection type
    const existingInfo = panelDescription.parentElement.querySelector('.correlation-info');
    if (existingInfo) {
        existingInfo.remove();
    }

    const infoMessage = document.createElement('p');
    infoMessage.style.fontSize = '0.9rem';
    infoMessage.style.color = '#666';
    infoMessage.style.marginTop = '0.5rem';
    infoMessage.className = 'correlation-info';

    if (title.startsWith('Category:')) {
        // Category selected - show all flavors in category highlighted
        infoMessage.innerHTML = `<strong>✨ All ${title.split(': ')[1]} flavors highlighted</strong> - Click another cell or close panel to clear`;
    } else if (title.startsWith('Flavor:') && hoveredFlavor) {
        // Flavor selected - show correlations
        infoMessage.innerHTML = `<strong>✨ Correlated flavors highlighted</strong> - Click another flavor or close panel to clear`;
    }

    if (infoMessage.innerHTML) {
        panelDescription.parentElement.appendChild(infoMessage);
    }
}

// Render products table
function renderProducts(products) {
    const tbody = document.getElementById('products-tbody');
    tbody.innerHTML = '';

    if (products.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align: center;">No products found</td></tr>';
        return;
    }

    products.forEach(product => {
        const row = document.createElement('tr');

        // Extract brand from relationship
        let brand = 'N/A';
        if (product.soldBy && product.soldBy.name) {
            brand = product.soldBy.name;
        }

        // Extract origin from relationships (may be multiple)
        let origins = [];
        if (product.origins && Array.isArray(product.origins)) {
            origins = product.origins.map(o => o.country).filter(c => c);
        }
        const originText = origins.length > 0 ? origins.join(', ') : 'N/A';

        // Extract flavors from relationships
        let flavors = [];
        if (product.flavors && Array.isArray(product.flavors)) {
            flavors = product.flavors.map(f => f.name).filter(n => n);
        }

        // Create product name with detail link
        const productNameHtml = `<a href="/product-detail.html?id=${product.productId}" class="product-link">${escapeHtml(product.productName || 'N/A')}</a>`;

        // Extract roast level
        let roastLevel = 'N/A';
        if (product.roastLevel && product.roastLevel.level) {
            roastLevel = product.roastLevel.level;
        }

        row.innerHTML = `
            <td>${productNameHtml}</td>
            <td>${escapeHtml(brand)}</td>
            <td>${escapeHtml(originText)}</td>
            <td>${escapeHtml(roastLevel)}</td>
            <td class="flavors-cell">${flavors.slice(0, 3).map(f => `<span class="flavor-tag">${escapeHtml(f)}</span>`).join(' ')}${flavors.length > 3 ? '...' : ''}</td>
            <td>${product.price ? `${product.currency || '£'}${product.price}` : 'N/A'}</td>
        `;

        tbody.appendChild(row);
    });
}

// Setup event listeners
function setupEventListeners() {
    const panel = document.getElementById('products-panel');

    // Helper function to close panel
    const closePanel = () => {
        panel.style.display = 'none';
        clearCorrelationHighlights();
    };

    // Close panel button
    document.getElementById('close-panel').addEventListener('click', closePanel);

    // Product search (keep existing functionality)
    const panelContent = panel.querySelector('.panel-content');
    if (panelContent) {
        panelContent.addEventListener('click', (e) => {
            e.stopPropagation();
        });
    }

    // Close on Escape key
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && panel.style.display === 'block') {
            closeModal();
        }
    });

    // Product search
    const searchInput = document.getElementById('product-search');
    let searchTimeout;
    searchInput.addEventListener('input', (e) => {
        clearTimeout(searchTimeout);
        searchTimeout = setTimeout(() => {
            filterProducts(e.target.value);
        }, 300);
    });
}

// Filter products by search term
function filterProducts(searchTerm) {
    const filtered = currentProducts.filter(product => {
        const searchLower = searchTerm.toLowerCase();
        return (
            (product.productName && product.productName.toLowerCase().includes(searchLower)) ||
            (product.brand && product.brand.toLowerCase().includes(searchLower)) ||
            (product.origin && product.origin.toLowerCase().includes(searchLower)) ||
            (product.process && product.process.toLowerCase().includes(searchLower))
        );
    });

    renderProducts(filtered);
    document.getElementById('product-count').textContent = `${filtered.length} products`;
}

// Show error message
function showError(message) {
    const errorDiv = document.getElementById('error');
    errorDiv.textContent = message;
    errorDiv.style.display = 'block';
    document.getElementById('loading').style.display = 'none';
}

// Capitalize first letter
function capitalize(str) {
    return str.charAt(0).toUpperCase() + str.slice(1);
}

// Escape HTML to prevent XSS
function escapeHtml(text) {
    const map = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    };
    return text ? String(text).replace(/[&<>"']/g, m => map[m]) : '';
}
