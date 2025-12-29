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
let panzoomInstance = null; // Panzoom instance for grid navigation

// Initialize on page load
document.addEventListener('DOMContentLoaded', async () => {
    try {
        await loadFlavorWheelData();
        renderFlavorGrid();
        setupEventListeners();
        setupPanzoom();
    } catch (error) {
        showError('Failed to load flavor wheel data: ' + error.message);
    }
});

// Setup panzoom for grid navigation
function setupPanzoom() {
    const gridElement = document.getElementById('flavor-grid');
    const container = document.getElementById('grid-container');

    if (!gridElement || typeof Panzoom === 'undefined') {
        console.warn('Panzoom not available');
        return;
    }

    const startScale = 0.5;

    // Get dimensions
    const containerRect = container.getBoundingClientRect();
    const gridWidth = gridElement.scrollWidth;
    const gridHeight = gridElement.scrollHeight;

    // Calculate offset to center the grid
    // We want the center of the grid to appear in the center of the container
    // With panzoom scaling from origin (0,0), we need to position the grid appropriately
    const scaledWidth = gridWidth * startScale;
    const scaledHeight = gridHeight * startScale;

    // Calculate pan offset to center the grid
    // The grid center is at (gridWidth/2, gridHeight/2). After scaling, this is at (scaledWidth/2, scaledHeight/2).
    // To show the grid center at the container center, we need to pan by the difference.
    const gridCenterX = scaledWidth / 2;
    const gridCenterY = scaledHeight / 2;
    const containerCenterX = containerRect.width / 2;
    const containerCenterY = containerRect.height / 2;

    // Pan offset: move view so grid center appears at container center
    // Subtract 500px adjustment for the smaller Attribute grid (~110 items)
    const panX = containerCenterX - gridCenterX - 500;
    const panY = containerCenterY - gridCenterY - 500;

    console.log('Centering:', {
        gridWidth, gridHeight,
        scaledWidth, scaledHeight,
        containerWidth: containerRect.width,
        containerHeight: containerRect.height,
        panX, panY
    });

    // Initialize panzoom with starting position
    panzoomInstance = Panzoom(gridElement, {
        maxScale: 2,
        minScale: 0.3,
        startScale: startScale,
        startX: panX,
        startY: panY,
        contain: false,
        cursor: 'grab'
    });

    // Enable mouse wheel zoom
    container.addEventListener('wheel', (e) => {
        e.preventDefault();
        panzoomInstance.zoomWithWheel(e);
    }, { passive: false });

    // Zoom controls
    document.getElementById('zoom-in').addEventListener('click', () => {
        panzoomInstance.zoomIn();
    });

    document.getElementById('zoom-out').addEventListener('click', () => {
        panzoomInstance.zoomOut();
    });

    document.getElementById('zoom-reset').addEventListener('click', () => {
        // Reset panzoom and re-center
        panzoomInstance.reset();
        gridElement.style.marginLeft = `${offsetX}px`;
        gridElement.style.marginTop = `${offsetY}px`;
    });
}

// Center the grid in the container
function centerGrid(animate = false) {
    const gridElement = document.getElementById('flavor-grid');
    const container = document.getElementById('grid-container');

    if (!panzoomInstance || !gridElement || !container) return;

    const containerRect = container.getBoundingClientRect();
    const scale = panzoomInstance.getScale();

    // Get grid's natural (unscaled) size
    const gridWidth = gridElement.scrollWidth;
    const gridHeight = gridElement.scrollHeight;

    // Scaled size
    const scaledWidth = gridWidth * scale;
    const scaledHeight = gridHeight * scale;

    // Calculate desired offset to center
    const desiredX = (containerRect.width - scaledWidth) / 2;
    const desiredY = (containerRect.height - scaledHeight) / 2;

    // Get current position from panzoom
    const currentPan = panzoomInstance.getPan();

    // Calculate how much to pan (relative movement)
    const panX = desiredX - currentPan.x;
    const panY = desiredY - currentPan.y;

    console.log('Centering grid:', {
        scale,
        gridWidth, gridHeight,
        scaledWidth, scaledHeight,
        desiredX, desiredY,
        currentPan,
        panX, panY
    });

    // Panzoom's pan() doesn't seem to update the CSS, so set it directly
    // Use the same format panzoom uses: scale first, then translate
    gridElement.style.transform = `scale(${scale}) translate(${desiredX}px, ${desiredY}px)`;

    console.log('Set transform to:', gridElement.style.transform);
}

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

    // Filter out any null or invalid categories/flavors (handle new structure with topFlavors/moreFlavors)
    if (flavorWheelData.categories) {
        flavorWheelData.categories = flavorWheelData.categories
            .filter(cat => cat && cat.name)
            .map(cat => ({
                ...cat,
                // Handle both old (flavors) and new (topFlavors/moreFlavors) structure
                topFlavors: (cat.topFlavors || cat.flavors || []).filter(f => f && f.name),
                moreFlavors: (cat.moreFlavors || []).filter(f => f && f.name),
                moreCount: cat.moreCount || 0
            }))
            .filter(cat => cat.topFlavors.length > 0 || cat.moreFlavors.length > 0);
    }

    console.log('Loaded flavor wheel data:', flavorWheelData);

    // Update stats (include both top and more flavors in total)
    const totalTopFlavors = flavorWheelData.totalFlavors || 0;
    const totalMoreFlavors = flavorWheelData.totalMoreFlavors || 0;
    document.getElementById('total-products').textContent = flavorWheelData.totalProducts || 0;
    document.getElementById('total-categories').textContent = flavorWheelData.totalCategories || 0;
    document.getElementById('total-flavors').textContent = totalTopFlavors + totalMoreFlavors;

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

    // Calculate cell spans based on absolute count (balanced distribution)
    // Returns { cols, rows } for rectangular cells
    const calculateCellSpan = (count) => {
        if (count === 0) return { cols: 1, rows: 1 };

        // Granular thresholds for visual variety
        if (count >= 400) return { cols: 4, rows: 3 };  // 400+: 4x3 (largest)
        if (count >= 200) return { cols: 4, rows: 2 };  // 200-399: 4x2
        if (count >= 100) return { cols: 3, rows: 3 };  // 100-199: 3x3
        if (count >= 50) return { cols: 3, rows: 2 };   // 50-99: 3x2
        if (count >= 30) return { cols: 3, rows: 1 };   // 30-49: 3x1 (wide bar)
        if (count >= 20) return { cols: 2, rows: 2 };   // 20-29: 2x2
        if (count >= 10) return { cols: 2, rows: 1 };   // 10-19: 2x1 (short bar)
        return { cols: 1, rows: 1 }; // <10 items: 1x1 cell
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

    // Group items by category with dynamic sizing - show ALL flavors
    const categoryGroups = {};

    flavorWheelData.categories.forEach(category => {
        const categoryItems = [];

        // Add category label itself
        const opacity = 0.7 + (category.productCount / maxCount) * 0.3;
        const size = calculateCellSpan(category.productCount);

        categoryItems.push({
            type: 'category',
            name: category.name,
            label: `${category.name.toUpperCase()}\n(${category.productCount})`,
            color: CATEGORY_COLORS[category.name] || '#95A5A6',
            opacity: opacity,
            productCount: category.productCount,
            cols: size.cols,
            rows: size.rows,
            onClick: () => selectCategory(category.name)
        });

        // Combine ALL flavors (top + more)
        const allFlavors = [
            ...(category.topFlavors || []),
            ...(category.moreFlavors || [])
        ];

        allFlavors.forEach(flavor => {
            // Skip if flavor name is the same as category name
            if (flavor.name.toLowerCase() === category.name.toLowerCase()) {
                return;
            }

            const flavorOpacity = 0.7 + (flavor.productCount / maxCount) * 0.3;
            const flavorSize = calculateCellSpan(flavor.productCount);

            categoryItems.push({
                type: 'flavor',
                name: flavor.name,
                category: category.name,
                label: `${capitalize(flavor.name)}\n(${flavor.productCount})`,
                color: CATEGORY_COLORS[category.name] || '#95A5A6',
                opacity: flavorOpacity,
                productCount: flavor.productCount,
                cols: flavorSize.cols,
                rows: flavorSize.rows,
                onClick: () => handleFlavorClick(flavor.name, category.name)
            });
        });

        // Sort items within category by product count (descending)
        categoryItems.sort((a, b) => {
            if (a.type === 'category') return -1;
            if (b.type === 'category') return 1;
            return (b.productCount || 0) - (a.productCount || 0);
        });

        categoryGroups[category.name] = {
            items: categoryItems,
            zone: categoryZones[category.name] || categoryZones['other']
        };
    });

    // Layout algorithm: Pack items in zones
    const cellSize = 40; // Smaller cell size for compact view
    const gap = 2;

    // Estimate grid size needed
    let totalArea = 1; // Center cell
    Object.values(categoryGroups).forEach(group => {
        totalArea += group.items.reduce((sum, item) => sum + (item.cols * item.rows), 0);
    });
    const gridSize = Math.ceil(Math.sqrt(totalArea)) + 10; // Extra padding for zones

    gridContainer.style.gridTemplateColumns = `repeat(${gridSize}, ${cellSize}px)`;
    gridContainer.style.gridTemplateRows = `repeat(${gridSize}, ${cellSize}px)`;
    gridContainer.style.gap = `${gap}px`;

    console.log('Grid size:', gridSize, 'Cell size:', cellSize, 'Total items:', Object.values(categoryGroups).reduce((sum, g) => sum + g.items.length, 0));

    // Create occupancy grid to track filled cells
    const occupied = Array(gridSize).fill(null).map(() => Array(gridSize).fill(false));

    // Place center "SCA Wheel" label
    const centerPos = Math.floor(gridSize / 2);
    const centerItem = {
        type: 'center',
        label: 'SCA Wheel',
        color: '#005F73',
        opacity: 1,
        cols: 1,
        rows: 1,
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

            // Check if this position can fit the item (using cols x rows)
            if (canPlaceItem(occupied, pos.row, pos.col, item.cols, item.rows, gridSize)) {
                placeItem(gridContainer, item, pos.row, pos.col, occupied, cellSize);
                itemIndex++;
            }
        }
    });

    console.log('Grid rendered, children:', gridContainer.children.length);
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

// Check if an item can be placed at given position (cols x rows)
function canPlaceItem(occupied, row, col, cols, rows, gridSize) {
    if (row + rows > gridSize || col + cols > gridSize) return false;

    for (let r = row; r < row + rows; r++) {
        for (let c = col; c < col + cols; c++) {
            if (occupied[r][c]) return false;
        }
    }
    return true;
}

// Place an item on the grid and mark occupied cells
function placeItem(container, item, row, col, occupied, cellSize) {
    const gap = 2;
    const width = cellSize * item.cols + (item.cols - 1) * gap;
    const height = cellSize * item.rows + (item.rows - 1) * gap;
    const cellDiv = createCell(item, width, height);

    // Mark cells as occupied
    for (let r = row; r < row + item.rows; r++) {
        for (let c = col; c < col + item.cols; c++) {
            if (r < occupied.length && c < occupied[0].length) {
                occupied[r][c] = true;
            }
        }
    }

    // Position using grid coordinates (span across multiple cells)
    cellDiv.style.gridColumn = `${col + 1} / span ${item.cols}`;
    cellDiv.style.gridRow = `${row + 1} / span ${item.rows}`;

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

    // Font size based on cell type and size - use max of cols/rows for sizing
    const cellSize = Math.max(cell.cols || 1, cell.rows || 1);
    if (cell.type === 'center') {
        label.style.fontSize = '10px';
        label.style.fontWeight = 'bold';
    } else if (cell.type === 'category') {
        // Scale font based on size
        const fontSize = cellSize >= 3 ? '11px' : cellSize >= 2 ? '10px' : '9px';
        label.style.fontSize = fontSize;
        label.style.fontWeight = 'bold';
    } else {
        // Flavor cells - compact sizes
        const fontSize = cellSize >= 3 ? '10px' : cellSize >= 2 ? '9px' : '8px';
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

    // Show products in the panel
    await selectFlavor(flavorName, categoryName);
}

// Show flavor correlations (highlight correlated cells AND show panel above grid)
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

    // Show correlations panel above the grid
    showCorrelationsPanel(flavorName, correlated);
}

// Show the correlated flavors in the inline panel
function showCorrelationsPanel(flavorName, correlated) {
    const listContainer = document.getElementById('correlations-list');
    if (!listContainer) return;

    listContainer.innerHTML = '';

    if (correlated.length === 0) {
        listContainer.innerHTML = '<span class="no-correlations">No strong correlations found</span>';
    } else {
        // Sort by percentage descending and show top correlations
        const sorted = [...correlated].sort((a, b) => b.percentage - a.percentage);
        sorted.forEach(item => {
            const badge = document.createElement('span');
            badge.className = 'correlation-badge';
            badge.innerHTML = `${capitalize(item.flavor)} <small>(${item.percentage}%)</small>`;
            badge.title = `${item.percentage}% of products with ${flavorName} also have ${item.flavor}`;
            badge.style.cursor = 'pointer';
            badge.addEventListener('click', () => {
                // Click to navigate to that flavor
                handleFlavorClick(item.flavor, item.category || 'other');
            });
            listContainer.appendChild(badge);
        });
    }
    // Note: panel visibility is controlled by showProductsPanel()
}

// Hide the correlations panel
function hideCorrelationsPanel() {
    const panel = document.getElementById('correlations-panel');
    panel.style.display = 'none';
}

// Clear correlation highlights
function clearCorrelationHighlights() {
    hoveredFlavor = null;

    // Hide the correlations panel
    hideCorrelationsPanel();

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

// Show products panel in the side panel
function showProductsPanel(title, description, products) {
    const placeholder = document.getElementById('panel-placeholder');
    const results = document.getElementById('panel-results');
    const panelTitle = document.getElementById('panel-title');
    const panelDescription = document.getElementById('panel-description');
    const productCount = document.getElementById('product-count');
    const correlationsPanel = document.getElementById('correlations-panel');

    panelTitle.textContent = title;
    panelDescription.textContent = description;
    productCount.textContent = `${products.length} products`;

    renderProducts(products);

    // Show/hide correlations section based on whether it's a flavor selection
    if (title.startsWith('Flavor:') && hoveredFlavor) {
        correlationsPanel.style.display = 'block';
    } else {
        correlationsPanel.style.display = 'none';
    }

    // Hide placeholder, show results
    placeholder.style.display = 'none';
    results.style.display = 'block';
}

// Render products as compact cards
function renderProducts(products) {
    const container = document.getElementById('products-list-compact');
    container.innerHTML = '';

    if (products.length === 0) {
        container.innerHTML = '<p class="no-products">No products found</p>';
        return;
    }

    products.forEach(product => {
        // Extract brand from relationship
        let brand = '';
        if (product.soldBy && product.soldBy.name) {
            brand = product.soldBy.name;
        }

        // Extract origin from relationships (may be multiple)
        let origins = [];
        if (product.origins && Array.isArray(product.origins)) {
            origins = product.origins.map(o => o.country).filter(c => c);
        }
        const originText = origins.length > 0 ? origins.join(', ') : '';

        // Extract price
        const price = product.price ? `${product.currency || '£'}${product.price}` : '';

        const card = document.createElement('a');
        card.href = `/product-detail.html?id=${product.productId}`;
        card.className = 'product-card-compact';
        card.innerHTML = `
            <div class="product-card-main">
                <span class="product-card-name">${escapeHtml(product.productName || 'Unknown')}</span>
                ${price ? `<span class="product-card-price">${price}</span>` : ''}
            </div>
            <div class="product-card-meta">
                ${brand ? `<span class="product-card-brand">${escapeHtml(brand)}</span>` : ''}
                ${originText ? `<span class="product-card-origin">${escapeHtml(originText)}</span>` : ''}
            </div>
        `;
        container.appendChild(card);
    });
}

// Setup event listeners
function setupEventListeners() {
    const placeholder = document.getElementById('panel-placeholder');
    const results = document.getElementById('panel-results');

    // Helper function to close panel (reset to placeholder)
    const closePanel = () => {
        results.style.display = 'none';
        placeholder.style.display = 'flex';
        clearCorrelationHighlights();
    };

    // Close panel button
    document.getElementById('close-panel').addEventListener('click', closePanel);

    // Close on Escape key
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && results.style.display === 'block') {
            closePanel();
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
