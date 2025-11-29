// Coffee Origins Map with Leaflet.js
let map;
let brandMarkers = [];
let originMarkers = [];
let producerMarkers = [];
let countryBoundaries = null;
let mapData = null;
let countryFlavorData = null; // Flavor data by country

// Storage for mappings
let brandToOrigins = {}; // Maps brand ID to related origin markers
let originToBrands = {}; // Maps origin coords to brand IDs
let brandColors = {}; // Maps brand ID to unique color
let countryToOrigins = {}; // Maps country name to origin markers
let countryNameToCode = {}; // Maps country names to ISO codes
let countryFlavorLabels = []; // Store flavor label markers
let connectionLines = []; // Store connection lines (brand → origin)

// State tracking
let currentlySelectedBrand = null;
let currentlyHoveredCountry = null;

// Filter states
let showBrands = true;
let showOrigins = true;
let showProducers = true;
let showClimate = false;
let showFlavors = false;

// Color palette for brands (vibrant, distinguishable colors)
const colorPalette = [
    '#FF6B6B', '#4ECDC4', '#45B7D1', '#FFA07A', '#98D8C8',
    '#F7DC6F', '#BB8FCE', '#85C1E2', '#F8B739', '#52B788',
    '#E67E22', '#3498DB', '#E74C3C', '#1ABC9C', '#9B59B6',
    '#F39C12', '#16A085', '#D35400', '#2ECC71', '#C0392B',
    '#8E44AD', '#27AE60', '#2980B9', '#E67E22', '#95A5A6',
    '#34495E', '#D68910', '#17A589', '#2874A6', '#943126'
];

// Initialize map
document.addEventListener('DOMContentLoaded', function() {
    initMap();
    loadCountryBoundaries();
    loadMapData();
    loadFlavorData();
    setupEventListeners();
});

function initMap() {
    // Create map centered on coffee belt (equator region)
    map = L.map('map', {
        center: [10, 0],
        zoom: 2,
        minZoom: 2,
        maxZoom: 18
    });

    // Add OpenStreetMap tile layer
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19
    }).addTo(map);
}

function setupEventListeners() {
    const brandsBtn = document.getElementById('filter-brands');
    const originsBtn = document.getElementById('filter-origins');
    const producersBtn = document.getElementById('filter-producers');
    const climateBtn = document.getElementById('filter-climate');
    const flavorsBtn = document.getElementById('filter-flavors');

    brandsBtn.addEventListener('click', function() {
        showBrands = !showBrands;
        this.classList.toggle('active');
        this.textContent = showBrands ? 'Hide Brands' : 'Show Brands';
        updateMapLayers();
    });

    originsBtn.addEventListener('click', function() {
        showOrigins = !showOrigins;
        this.classList.toggle('active');
        this.textContent = showOrigins ? 'Hide Origins' : 'Show Origins';
        updateMapLayers();
    });

    producersBtn.addEventListener('click', function() {
        showProducers = !showProducers;
        this.classList.toggle('active');
        this.textContent = showProducers ? 'Hide Producers' : 'Show Producers';
        updateMapLayers();
    });

    climateBtn.addEventListener('click', function() {
        showClimate = !showClimate;
        this.classList.toggle('active');
        this.textContent = showClimate ? 'Hide Climate' : 'Show Climate';
        updateFlavorLabelsVisibility();
    });

    flavorsBtn.addEventListener('click', function() {
        showFlavors = !showFlavors;
        this.classList.toggle('active');
        this.textContent = showFlavors ? 'Hide Flavors' : 'Show Flavors';
        updateFlavorLabelsVisibility();
    });

    // Show Connections toggle - controls whether lines are drawn on brand click
    const connectionsBtn = document.getElementById('filter-connections');
    if (connectionsBtn) {
        connectionsBtn.addEventListener('click', function() {
            this.classList.toggle('active');
            const isActive = this.classList.contains('active');
            this.textContent = isActive ? 'Hide Connections' : 'Show Connections';

            // If turning off connections, clear any existing lines
            if (!isActive) {
                clearConnectionLines();
            } else if (currentlySelectedBrand) {
                // If turning on and brand is selected, draw lines
                drawBrandConnectionLines(currentlySelectedBrand);
            }
        });
    }

    // Click on map to deselect brand and clear connections
    map.on('click', function(e) {
        // Only deselect if clicking on the map background (not on a marker)
        if (e.originalEvent.target === map.getContainer().querySelector('.leaflet-tile-pane') ||
            e.originalEvent.target.classList.contains('leaflet-container')) {
            if (currentlySelectedBrand !== null) {
                const prevBrand = brandMarkers.find(b => b.data.id === currentlySelectedBrand);
                if (prevBrand) {
                    updateBrandMarkerSize(prevBrand.marker, 16, 2);
                }
                currentlySelectedBrand = null;
                resetOriginColors();
                clearConnectionLines();
            }
        }
    });
}

async function loadCountryBoundaries() {
    try {
        // Load simplified world boundaries GeoJSON from Natural Earth
        const response = await fetch('https://raw.githubusercontent.com/johan/world.geo.json/master/countries.geo.json');
        const geojson = await response.json();

        // Add to map with styling
        countryBoundaries = L.geoJSON(geojson, {
            style: {
                fillColor: 'transparent',
                weight: 1,
                opacity: 0.3,
                color: '#999',
                fillOpacity: 0
            },
            onEachFeature: onEachCountryFeature
        }).addTo(map);

        console.log('Country boundaries loaded');
    } catch (error) {
        console.error('Error loading country boundaries:', error);
    }
}

function onEachCountryFeature(feature, layer) {
    const countryName = feature.properties.name;
    const countryCode = feature.id; // ISO code

    // Store mapping
    if (countryName) {
        countryNameToCode[countryName.toLowerCase()] = countryCode;
    }

    // Hover effects
    layer.on({
        mouseover: function(e) {
            const layer = e.target;
            layer.setStyle({
                weight: 3,
                color: '#FFD700',
                fillOpacity: 0.2,
                fillColor: '#FFD700'
            });

            // Highlight related origin markers
            highlightOriginsInCountry(countryName);
            currentlyHoveredCountry = countryName;
        },
        mouseout: function(e) {
            const layer = e.target;
            layer.setStyle({
                weight: 1,
                opacity: 0.3,
                color: '#999',
                fillOpacity: 0,
                fillColor: 'transparent'
            });

            // Remove origin highlights (only if no brand is selected)
            if (!currentlySelectedBrand) {
                resetOriginColors();
            } else {
                // Restore brand color highlighting
                highlightBrandOrigins(currentlySelectedBrand);
            }
            currentlyHoveredCountry = null;
        }
        // Removed click event - climate button will handle this instead
    });
}

function highlightOriginsInCountry(countryName) {
    const normalizedCountry = normalizeCountryName(countryName);
    const originsInCountry = countryToOrigins[normalizedCountry] || [];

    originsInCountry.forEach(originData => {
        if (!originData || !originData.marker) return; // Safety check

        const marker = originData.marker;
        const element = marker.getElement();
        if (element) {
            element.style.fill = '#FFD700'; // Change color to yellow
            element.classList.add('origin-highlighted');
        }
    });
}

function resetOriginColors() {
    originMarkers.forEach(originData => {
        if (!originData || !originData.marker) return; // Safety check

        const marker = originData.marker;
        // Use 4 decimal precision to match the mapping keys
        const coordKey = `${originData.data.latitude.toFixed(4)},${originData.data.longitude.toFixed(4)}`;

        // Get the default color for this origin (from its brands)
        const defaultColor = getOriginDefaultColor(coordKey);

        const element = marker.getElement();
        if (element) {
            element.style.fill = defaultColor;
            element.classList.remove('origin-highlighted');
        }
    });
}

function getOriginDefaultColor(coordKey) {
    const brandIds = originToBrands[coordKey] || [];

    if (brandIds.length === 0) {
        return '#8FBC8F'; // Default green if no brands
    }

    if (brandIds.length === 1) {
        return brandColors[brandIds[0]] || '#8FBC8F';
    }

    // Multiple brands - use first brand's color (could be enhanced to show multiple colors)
    return brandColors[brandIds[0]] || '#8FBC8F';
}

async function loadMapData() {
    showLoading(true);
    try {
        // Try static cache file first (no database queries!)
        let response = await fetch('/cache/map-data.json');

        if (!response.ok) {
            // Fallback to API if cache doesn't exist
            console.warn('Cache file not found, falling back to API');
            response = await fetch('/api/map/data');

            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
        } else {
            console.log('Loaded map data from cache (no DB queries)');
        }

        mapData = await response.json();
        console.log('Loaded map data:', mapData);
        renderMap();
    } catch (error) {
        console.error('Error loading map data:', error);
        alert('Failed to load map data. Please try again later.');
    } finally {
        showLoading(false);
    }
}

async function loadFlavorData() {
    try {
        // Try static cache file first (no database queries!)
        let response = await fetch('/cache/flavors-by-country.json');

        if (!response.ok) {
            // Fallback to API if cache doesn't exist
            console.warn('Flavor cache file not found, falling back to API');
            response = await fetch('/api/map/flavors-by-country');

            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
        } else {
            console.log('Loaded flavor data from cache (no DB queries)');
        }

        countryFlavorData = await response.json();
        console.log('Loaded flavor data:', countryFlavorData);

        // Don't add flavor labels by default - wait for user to toggle them on
        // Labels will be added when showClimate or showFlavors are toggled to true
    } catch (error) {
        console.error('Error loading flavor data:', error);
    }
}

function addFlavorLabelsToCountries() {
    if (!countryFlavorData || !countryBoundaries) return;

    // Clear existing labels
    countryFlavorLabels.forEach(label => map.removeLayer(label));
    countryFlavorLabels = [];

    countryFlavorData.forEach(countryData => {
        const countryName = countryData.countryName;
        const topFlavors = countryData.topFlavors;

        if (!topFlavors || topFlavors.length === 0) return;

        // Find the country feature in the GeoJSON to get centroid and country code
        let countryCenter = null;
        let countryCode = null;
        countryBoundaries.eachLayer(layer => {
            if (layer.feature && layer.feature.properties.name === countryName) {
                // Get the center of the country polygon
                const bounds = layer.getBounds();
                countryCenter = bounds.getCenter();
                countryCode = layer.feature.id; // ISO code
            }
        });

        if (!countryCenter) return;

        // Format flavor text: "Berry 45% • Chocolate 32% • Citrus 28%"
        const flavorText = topFlavors
            .slice(0, 3) // Show only top 3 flavors for readability
            .map(f => `${capitalizeFirstLetter(f.flavor)} ${Math.round(f.percentage)}%`)
            .join(' • ');

        // Center the label on the country center
        const offsetLat = countryCenter.lat;
        const offsetLng = countryCenter.lng;

        // Build content based on toggle states
        let labelContent = '<div class="flavor-label-content">';

        // Show country name
        labelContent += `<div class="country-name">${countryName}</div>`;

        // Conditionally show flavors
        if (showFlavors) {
            labelContent += `<div class="flavor-list">${flavorText}</div>`;
        }

        // Conditionally show climate button
        if (showClimate) {
            labelContent += `<button class="climate-btn" data-country="${countryName}" data-code="${countryCode}" data-lat="${countryCenter.lat}" data-lng="${countryCenter.lng}">
                🌡️ Climate
            </button>`;
        }

        labelContent += '</div>';

        // Create a permanent label (tooltip) for this country with climate button
        const label = L.tooltip({
            permanent: true,
            direction: 'center',
            className: 'country-flavor-label',
            opacity: 1,
            interactive: true, // Make interactive so climate button can be clicked
            offset: [0, 0] // No offset, center it
        })
        .setLatLng([offsetLat, offsetLng])
        .setContent(labelContent)
        .addTo(map);

        countryFlavorLabels.push(label);
    });

    // Setup climate button event listeners after labels are added
    setTimeout(() => {
        setupClimateButtons();
    }, 100);

    console.log(`Added ${countryFlavorLabels.length} flavor labels to countries`);
}

function updateFlavorLabelsVisibility() {
    // If both climate and flavors are hidden, remove all labels
    if (!showClimate && !showFlavors) {
        countryFlavorLabels.forEach(label => map.removeLayer(label));
        countryFlavorLabels = [];
        console.log('All country labels hidden');
        return;
    }

    // Otherwise, re-render labels with updated toggle states
    addFlavorLabelsToCountries();
}

function capitalizeFirstLetter(string) {
    if (!string) return '';
    return string.charAt(0).toUpperCase() + string.slice(1);
}

/**
 * Setup climate button event listeners
 */
function setupClimateButtons() {
    const climateButtons = document.querySelectorAll('.climate-btn');

    climateButtons.forEach(button => {
        button.addEventListener('click', function(e) {
            e.stopPropagation(); // Prevent event bubbling
            e.preventDefault(); // Prevent default behavior

            const countryName = this.getAttribute('data-country');
            const countryCode = this.getAttribute('data-code');
            const lat = parseFloat(this.getAttribute('data-lat'));
            const lng = parseFloat(this.getAttribute('data-lng'));

            console.log('Climate button clicked:', countryName);

            showWeatherPopup(countryName, countryCode, L.latLng(lat, lng));
        });

        // Also prevent mousedown/mouseup from propagating
        button.addEventListener('mousedown', function(e) {
            e.stopPropagation();
        });
        button.addEventListener('mouseup', function(e) {
            e.stopPropagation();
        });
    });

    console.log(`Setup ${climateButtons.length} climate buttons`);
}

function renderMap() {
    if (!mapData) return;

    // Clear existing markers
    clearMapLayers();

    // Assign colors to brands
    assignBrandColors();

    // Build country-to-origins mapping first
    buildCountryToOriginsMapping();

    // Build brand-to-origins mapping
    buildBrandToOriginsMapping();

    // Build origin-to-brands reverse mapping
    buildOriginToBrandsMapping();

    // Render brand markers
    if (mapData.brands && mapData.brands.length > 0) {
        mapData.brands.forEach(brand => {
            const marker = createBrandMarker(brand);
            brandMarkers.push({ marker, data: brand });
        });
    }

    // Render origin markers (after brand-to-origin mapping is built)
    if (mapData.origins && mapData.origins.length > 0) {
        mapData.origins.forEach(origin => {
            const marker = createOriginMarker(origin);
            originMarkers.push({ marker, data: origin });
        });
    }

    // Render producer markers
    if (mapData.producers && mapData.producers.length > 0) {
        mapData.producers.forEach(producer => {
            const marker = createProducerMarker(producer);
            producerMarkers.push({ marker, data: producer });
        });
    }

    // Apply current filters
    updateMapLayers();

    // Fit map to show all markers
    fitMapToMarkers();
}

function assignBrandColors() {
    brandColors = {};
    if (!mapData.brands) return;

    mapData.brands.forEach((brand, index) => {
        brandColors[brand.id] = colorPalette[index % colorPalette.length];
    });
}

function buildCountryToOriginsMapping() {
    countryToOrigins = {};

    if (!mapData.origins) return;

    mapData.origins.forEach(origin => {
        const normalizedCountry = normalizeCountryName(origin.country);
        if (!countryToOrigins[normalizedCountry]) {
            countryToOrigins[normalizedCountry] = [];
        }
        // Store origin data (will add marker reference after creation)
        countryToOrigins[normalizedCountry].push({ data: origin, marker: null });
    });
}

function buildBrandToOriginsMapping() {
    brandToOrigins = {};

    if (!mapData.connections) return;

    // Build mapping from brand-origin connections
    mapData.connections.forEach(conn => {
        if (conn.type === 'brand-origin' && conn.fromId) {
            if (!brandToOrigins[conn.fromId]) {
                brandToOrigins[conn.fromId] = new Set();
            }
            // Store the origin coordinates with 4 decimal precision to match deduplicated origins
            const coordKey = `${conn.toLat.toFixed(4)},${conn.toLon.toFixed(4)}`;
            brandToOrigins[conn.fromId].add(coordKey);
        }
    });
}

function buildOriginToBrandsMapping() {
    originToBrands = {};

    if (!mapData.connections) return;

    // Build reverse mapping: origin -> brands
    mapData.connections.forEach(conn => {
        if (conn.type === 'brand-origin' && conn.fromId) {
            // Use 4 decimal precision to match deduplicated origins
            const coordKey = `${conn.toLat.toFixed(4)},${conn.toLon.toFixed(4)}`;
            if (!originToBrands[coordKey]) {
                originToBrands[coordKey] = [];
            }
            if (!originToBrands[coordKey].includes(conn.fromId)) {
                originToBrands[coordKey].push(conn.fromId);
            }
        }
    });
}

function createBrandMarker(brand) {
    const brandColor = brandColors[brand.id];

    // Create custom square icon using DivIcon
    const squareIcon = L.divIcon({
        className: 'brand-square-marker',
        html: `<div style="
            width: 16px;
            height: 16px;
            background-color: ${brandColor};
            border: 2px solid #333;
            transform: translate(-50%, -50%);
            cursor: pointer;
        "></div>`,
        iconSize: [16, 16],
        iconAnchor: [8, 8]
    });

    const marker = L.marker([brand.latitude, brand.longitude], {
        icon: squareIcon,
        riseOnHover: true,
        zIndexOffset: 100 // Brand markers at mid-level
    });

    // Store original properties for state management
    marker.brandId = brand.id;
    marker.brandColor = brandColor;

    const popupContent = `
        <div class="popup-title">
            <a href="/products.html?brandId=${brand.id}" class="popup-link" style="color: #005F73; text-decoration: none;">☕ ${brand.name}</a>
        </div>
        <div class="popup-info">
            <strong>Country:</strong> ${brand.country || 'Unknown'}<br>
            <strong>Products:</strong> <a href="/products.html?brandId=${brand.id}" class="popup-link">${brand.productCount}</a><br>
            ${brand.website ? `<a href="${brand.website}" target="_blank" class="popup-link">Visit Website →</a>` : ''}
        </div>
    `;

    marker.bindPopup(popupContent);

    // Add hover and click interactions
    marker.on('mouseover', function() {
        if (currentlySelectedBrand !== brand.id) {
            highlightBrandOrigins(brand.id);
            // Make brand marker larger on hover
            updateBrandMarkerSize(marker, 20, 3);
        }
    });

    marker.on('mouseout', function() {
        if (currentlySelectedBrand !== brand.id) {
            resetOriginColors();
            // Reset brand marker size if not selected
            updateBrandMarkerSize(marker, 16, 2);
        }
    });

    marker.on('click', function(e) {
        // Stop event propagation to prevent map click handler
        L.DomEvent.stopPropagation(e);

        // Toggle selection
        if (currentlySelectedBrand === brand.id) {
            currentlySelectedBrand = null;
            resetOriginColors();
            clearConnectionLines();
            updateBrandMarkerSize(marker, 16, 2);
        } else {
            // Reset previous selection
            if (currentlySelectedBrand !== null) {
                const prevBrand = brandMarkers.find(b => b.data.id === currentlySelectedBrand);
                if (prevBrand) {
                    updateBrandMarkerSize(prevBrand.marker, 16, 2);
                }
            }
            currentlySelectedBrand = brand.id;
            highlightBrandOrigins(brand.id);
            // Draw connection lines if toggle is active
            drawBrandConnectionLines(brand.id);
            // Make selected brand marker more prominent
            updateBrandMarkerSize(marker, 20, 3);
        }
    });

    return marker;
}

function updateBrandMarkerSize(marker, size, borderWidth) {
    const element = marker.getElement();
    if (element) {
        const square = element.querySelector('div');
        if (square) {
            square.style.width = `${size}px`;
            square.style.height = `${size}px`;
            square.style.borderWidth = `${borderWidth}px`;
        }
    }
}

function highlightBrandOrigins(brandId) {
    const brandColor = brandColors[brandId];
    const originCoords = brandToOrigins[brandId] || new Set();

    // Reset all origins first
    resetOriginColors();

    // Highlight origins related to this brand
    originMarkers.forEach(originData => {
        // Use 4 decimal precision to match the mapping keys
        const coordKey = `${originData.data.latitude.toFixed(4)},${originData.data.longitude.toFixed(4)}`;
        if (originCoords.has(coordKey)) {
            const marker = originData.marker;
            const element = marker.getElement();
            if (element) {
                element.style.fill = brandColor;
                element.classList.add('origin-highlighted');
            }
        }
    });
}

/**
 * Draw curved connection lines from a brand to all its coffee origins
 */
function drawBrandConnectionLines(brandId) {
    // Check if connections toggle is active
    const connectionsBtn = document.getElementById('filter-connections');
    if (!connectionsBtn || !connectionsBtn.classList.contains('active')) {
        return;
    }

    // Clear any existing lines first
    clearConnectionLines();

    const brand = brandMarkers.find(b => b.data.id === brandId);
    if (!brand) return;

    const brandColor = brandColors[brandId];
    const brandLatLng = [brand.data.latitude, brand.data.longitude];
    const originCoords = brandToOrigins[brandId] || new Set();

    // Draw line to each origin
    originCoords.forEach(coordKey => {
        const [lat, lng] = coordKey.split(',').map(parseFloat);

        // Create a curved line using a bezier-like path
        const line = createCurvedLine(brandLatLng, [lat, lng], brandColor);
        line.addTo(map);
        connectionLines.push(line);
    });
}

/**
 * Create a curved line between two points using intermediate points
 */
function createCurvedLine(startLatLng, endLatLng, color) {
    // Calculate midpoint and offset it for curve effect
    const midLat = (startLatLng[0] + endLatLng[0]) / 2;
    const midLng = (startLatLng[1] + endLatLng[1]) / 2;

    // Calculate distance for curve amplitude
    const latDiff = Math.abs(startLatLng[0] - endLatLng[0]);
    const lngDiff = Math.abs(startLatLng[1] - endLatLng[1]);
    const distance = Math.sqrt(latDiff * latDiff + lngDiff * lngDiff);

    // Curve offset perpendicular to line (scaled by distance)
    const curveOffset = distance * 0.15;

    // Determine curve direction (perpendicular to the line)
    const angle = Math.atan2(endLatLng[0] - startLatLng[0], endLatLng[1] - startLatLng[1]);
    const perpAngle = angle + Math.PI / 2;

    const controlLat = midLat + Math.sin(perpAngle) * curveOffset;
    const controlLng = midLng + Math.cos(perpAngle) * curveOffset;

    // Create smooth curve with multiple points
    const curvePoints = [];
    const steps = 20;

    for (let i = 0; i <= steps; i++) {
        const t = i / steps;
        // Quadratic bezier curve formula
        const lat = Math.pow(1 - t, 2) * startLatLng[0] +
                    2 * (1 - t) * t * controlLat +
                    Math.pow(t, 2) * endLatLng[0];
        const lng = Math.pow(1 - t, 2) * startLatLng[1] +
                    2 * (1 - t) * t * controlLng +
                    Math.pow(t, 2) * endLatLng[1];
        curvePoints.push([lat, lng]);
    }

    // Create polyline with the curved points
    return L.polyline(curvePoints, {
        color: color,
        weight: 2,
        opacity: 0.7,
        dashArray: '5, 5', // Dashed line for visual appeal
        className: 'connection-line'
    });
}

/**
 * Clear all connection lines from the map
 */
function clearConnectionLines() {
    connectionLines.forEach(line => {
        map.removeLayer(line);
    });
    connectionLines = [];
}

function createOriginMarker(origin) {
    // Use 4 decimal precision to match the mapping keys
    const coordKey = `${origin.latitude.toFixed(4)},${origin.longitude.toFixed(4)}`;
    const defaultColor = getOriginDefaultColor(coordKey);

    // Scale radius based on product count
    // 1 product = 6px, 2-3 = 8px, 4-5 = 10px, 6+ = 12px
    let radius = 6;
    const productCount = origin.productCount || 1;
    if (productCount >= 6) {
        radius = 12;
    } else if (productCount >= 4) {
        radius = 10;
    } else if (productCount >= 2) {
        radius = 8;
    }

    const marker = L.circleMarker([origin.latitude, origin.longitude], {
        radius: radius,
        fillColor: defaultColor,
        color: '#2F4F2F',
        weight: 2,
        opacity: 1,
        fillOpacity: 0.8,
        className: 'origin-marker',
        pane: 'markerPane', // Ensure it's in the marker pane (top layer)
        interactive: true // Ensure it responds to mouse events
    });

    // Note: circleMarker doesn't support setZIndexOffset, z-index controlled via CSS

    const displayName = origin.region
        ? `${origin.region}, ${origin.country}`
        : origin.country;

    const popupContent = `
        <div class="popup-title">🌍 ${displayName}</div>
        <div class="popup-info">
            <strong>Coffee Origin</strong><br>
            <strong>Products:</strong> ${origin.productCount}<br>
            <em>Click to see related brands and products</em>
        </div>
    `;

    marker.bindPopup(popupContent);

    // Add click handler to show related brands and products
    marker.on('click', async function() {
        await showOriginProducts(origin);
    });

    // Add mouseover handler to bring marker to front (highest z-index in SVG)
    marker.on('mouseover', function() {
        this.bringToFront();
    });

    // Update the mapping with marker reference
    const normalizedCountry = normalizeCountryName(origin.country);
    if (countryToOrigins[normalizedCountry]) {
        const originEntry = countryToOrigins[normalizedCountry].find(o =>
            o.data.latitude === origin.latitude && o.data.longitude === origin.longitude
        );
        if (originEntry) {
            originEntry.marker = marker;
        }
    }

    return marker;
}

async function showOriginProducts(origin) {
    try {
        // Build query parameter - try region first, fallback to country
        let originQuery = origin.country;

        // If there's a region, search by "region, country" format
        if (origin.region && origin.region.trim() !== '') {
            originQuery = origin.region;
        }

        console.log('Fetching products for origin:', originQuery);

        const response = await fetch(`/api/products/origin/${encodeURIComponent(originQuery)}/with-brand`);

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const products = await response.json();

        if (!products || products.length === 0) {
            // Try with just country if region search failed
            if (origin.region && origin.region.trim() !== '') {
                console.log('No products found with region, trying country:', origin.country);
                const countryResponse = await fetch(`/api/products/origin/${encodeURIComponent(origin.country)}/with-brand`);
                const countryProducts = await countryResponse.json();

                if (!countryProducts || countryProducts.length === 0) {
                    alert(`No products found for ${origin.region ? origin.region + ', ' : ''}${origin.country}`);
                    return;
                }

                displayProductsPopup(origin, countryProducts);
                return;
            }

            alert(`No products found for ${origin.country}`);
            return;
        }

        displayProductsPopup(origin, products);

    } catch (error) {
        console.error('Error fetching origin products:', error);
        alert('Failed to load products. Please try again.');
    }
}

function displayProductsPopup(origin, products) {
    // Create popup content with products
    let content = `<div style="max-height: 400px; overflow-y: auto; min-width: 300px;">`;
    content += `<h3 style="color: #005F73; margin-bottom: 10px;">☕ Products from ${origin.region || origin.country}</h3>`;
    content += `<div style="font-size: 0.9rem; color: #666; margin-bottom: 10px;">Total: ${products.length} product${products.length !== 1 ? 's' : ''}</div>`;

    products.forEach(product => {
        const productUrl = product.sellerUrl || '#';
        const price = product.price ? `£${product.price}` : 'N/A';
        const productName = product.productName || 'Unnamed Product';
        const brandName = product.brandName || 'Unknown Brand';

        // Get brand color
        const brandColor = getBrandColorByName(brandName);

        content += `<div style="margin: 5px 0; padding: 8px; background: white; border-radius: 3px; border-left: 3px solid ${brandColor};">`;

        // Brand name at the top with brand color
        content += `<div style="color: ${brandColor}; font-weight: 600; font-size: 0.85rem; margin-bottom: 4px;">${brandName}</div>`;

        // Product name with detail link
        content += `<a href="/product-detail.html?id=${product.id}" style="color: #005F73; text-decoration: none; font-weight: 500; display: block;">${productName}</a>`;

        // Product details
        content += `<div style="margin-top: 4px; display: flex; align-items: center; gap: 8px;">`;
        content += `<small style="color: #666;">Price: ${price}</small>`;

        if (product.process) {
            content += `<small style="color: #666;"> • ${product.process}</small>`;
        }
        if (product.variety) {
            content += `<small style="color: #666;"> • ${product.variety}</small>`;
        }
        if (product.altitude) {
            content += `<small style="color: #666;"> • ${product.altitude}</small>`;
        }
        content += `</div></div>`;
    });

    content += `</div>`;

    // Create a custom popup
    const popup = L.popup({ maxWidth: 450, maxHeight: 500 })
        .setLatLng([origin.latitude, origin.longitude])
        .setContent(content)
        .openOn(map);
}

function getBrandColorByName(brandName) {
    // Find brand color by name
    if (!mapData.brands) return '#005F73';

    const brand = mapData.brands.find(b => b.name === brandName);
    if (brand && brandColors[brand.id]) {
        return brandColors[brand.id];
    }

    // Fallback color
    return '#005F73';
}

function createProducerMarker(producer) {
    const marker = L.circleMarker([producer.latitude, producer.longitude], {
        radius: 5,
        fillColor: '#CD853F',
        color: '#8B4513',
        weight: 2,
        opacity: 1,
        fillOpacity: 0.8,
        className: 'producer-marker',
        pane: 'markerPane', // Ensure it's in the marker pane (top layer)
        interactive: true // Ensure it responds to mouse events
    });

    // Note: circleMarker doesn't support setZIndexOffset, z-index controlled via CSS

    const location = [
        producer.city,
        producer.region,
        producer.country
    ].filter(Boolean).join(', ');

    const popupContent = `
        <div class="popup-title">🌱 ${producer.name}</div>
        <div class="popup-info">
            <strong>Producer/Farm</strong><br>
            <strong>Location:</strong> ${location}<br>
        </div>
    `;

    marker.bindPopup(popupContent);

    // Add mouseover handler to bring marker to front (highest z-index in SVG)
    marker.on('mouseover', function() {
        this.bringToFront();
    });

    return marker;
}

function updateMapLayers() {
    // Remove all layers
    brandMarkers.forEach(m => map.removeLayer(m.marker));
    originMarkers.forEach(m => map.removeLayer(m.marker));
    producerMarkers.forEach(m => map.removeLayer(m.marker));

    // Add back based on filter states
    if (showBrands) {
        brandMarkers.forEach(m => m.marker.addTo(map));
    }

    if (showOrigins) {
        originMarkers.forEach(m => m.marker.addTo(map));
    }

    if (showProducers) {
        producerMarkers.forEach(m => m.marker.addTo(map));
    }
}

function clearMapLayers() {
    brandMarkers.forEach(m => map.removeLayer(m.marker));
    originMarkers.forEach(m => map.removeLayer(m.marker));
    producerMarkers.forEach(m => map.removeLayer(m.marker));
    clearConnectionLines();

    brandMarkers = [];
    originMarkers = [];
    producerMarkers = [];
    brandToOrigins = {};
    originToBrands = {};
}

function fitMapToMarkers() {
    const allMarkers = [
        ...brandMarkers.map(m => m.marker),
        ...originMarkers.map(m => m.marker),
        ...producerMarkers.map(m => m.marker)
    ];

    if (allMarkers.length === 0) {
        // If no markers, stay at default view
        return;
    }

    const group = L.featureGroup(allMarkers);
    map.fitBounds(group.getBounds(), {
        padding: [50, 50],
        maxZoom: 5
    });
}

function normalizeCountryName(countryName) {
    if (!countryName) return '';

    // Normalize common variations
    const normalized = countryName.toLowerCase().trim();
    const mappings = {
        'uk': 'united kingdom',
        'usa': 'united states',
        'us': 'united states',
        'united states of america': 'united states',
        'brasil': 'brazil',
        'columbia': 'colombia',
        'costa rica': 'costa rica',
        'papua new guinea': 'papua new guinea',
        'democratic republic of the congo': 'democratic republic of congo',
        'ivory coast': "côte d'ivoire"
    };

    return mappings[normalized] || normalized;
}

function showLoading(show) {
    const loadingElement = document.getElementById('loading');
    if (show) {
        loadingElement.classList.remove('hidden');
    } else {
        loadingElement.classList.add('hidden');
    }
}

// Utility: Format number with commas
function formatNumber(num) {
    return num.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

// ==================== Weather Chart Functionality ====================

let currentWeatherChart = null; // Track active chart to destroy before creating new one

/**
 * Show weather data popup when a country is clicked
 */
async function showWeatherPopup(countryName, countryCode, latlng) {
    // Normalize country code
    const code = normalizeCountryCode(countryCode);

    if (!code) {
        console.warn('Unable to determine country code for:', countryName);
        return;
    }

    console.log(`Fetching weather data for ${countryName} (${code})`);

    // Show loading popup first
    showLoadingPopup(countryName, latlng);

    try {
        // Try to fetch cached weather data
        let response = await fetch(`/api/map/weather/${code}`);

        // If 404 (no data), fetch from Open-Meteo and cache it
        if (response.status === 404) {
            console.log(`No cached data for ${code}, fetching from Open-Meteo...`);

            // Get origin coordinates for this country
            const origin = await findOriginForCountry(countryName);

            if (!origin || !origin.latitude || !origin.longitude) {
                console.log(`No origin found for ${countryName}, cannot fetch weather data`);
                showNoDataPopup(countryName, latlng);
                return;
            }

            console.log(`Found origin for ${countryName}:`, origin);

            // Fetch and cache weather data
            const fetchResponse = await fetch(
                `/api/map/weather/fetch/${code}?countryName=${encodeURIComponent(countryName)}&latitude=${origin.latitude}&longitude=${origin.longitude}&force=false`,
                { method: 'POST' }
            );

            if (!fetchResponse.ok) {
                const errorText = await fetchResponse.text();
                console.error('Failed to fetch weather data:', errorText);
                showErrorPopup(countryName, latlng);
                return;
            }

            console.log(`Successfully fetched and cached weather data for ${code}`);

            // Now try to get the cached data again
            response = await fetch(`/api/map/weather/${code}`);
        }

        if (!response.ok) {
            console.error('Weather API error:', response.status);
            showErrorPopup(countryName, latlng);
            return;
        }

        const weatherData = await response.json();

        // Create and show popup with chart
        createWeatherPopup(weatherData, latlng);

    } catch (error) {
        console.error('Error fetching weather data:', error);
        showErrorPopup(countryName, latlng);
    }
}

/**
 * Find origin coordinates for a country name
 */
async function findOriginForCountry(countryName) {
    // Look through already loaded origin markers
    const normalizedCountry = normalizeCountryName(countryName);
    const originsForCountry = countryToOrigins[normalizedCountry] || [];

    if (originsForCountry.length > 0) {
        console.log(`Found origin in loaded data for ${countryName}`);
        return originsForCountry[0].data;
    }

    // If not found in loaded data, try to fetch from API
    try {
        const response = await fetch('/api/map/origins');
        const origins = await response.json();

        console.log(`Searching through ${origins.length} origins for ${countryName}`);

        // Try exact match first
        let match = origins.find(o =>
            normalizeCountryName(o.country) === normalizedCountry
        );

        // If no exact match, try partial match (e.g., "United States" in "United States of America")
        if (!match) {
            match = origins.find(o => {
                const originCountry = normalizeCountryName(o.country);
                return normalizedCountry.includes(originCountry) || originCountry.includes(normalizedCountry);
            });
        }

        if (match) {
            console.log(`Found match: ${match.country} at (${match.latitude}, ${match.longitude})`);
        } else {
            console.log(`No match found. Available countries:`, origins.map(o => o.country).join(', '));
        }

        return match || null;
    } catch (error) {
        console.error('Error finding origin:', error);
        return null;
    }
}

/**
 * Normalize country code (handle various formats)
 */
function normalizeCountryCode(code) {
    if (!code) return null;

    // Convert to uppercase and handle 3-letter codes
    let normalized = code.toString().toUpperCase();

    // Map common 3-letter codes to 2-letter ISO codes
    const codeMapping = {
        'COL': 'CO', 'ETH': 'ET', 'BRA': 'BR', 'KEN': 'KE',
        'GTM': 'GT', 'HND': 'HN', 'PER': 'PE', 'IDN': 'ID',
        'VNM': 'VN', 'MEX': 'MX', 'NIC': 'NI', 'CRI': 'CR',
        'RWA': 'RW', 'UGA': 'UG', 'TZA': 'TZ', 'IND': 'IN',
        'PAN': 'PA', 'ECU': 'EC', 'BOL': 'BO', 'VEN': 'VE'
    };

    // If it's a 3-letter code, try to map it
    if (normalized.length === 3) {
        return codeMapping[normalized] || normalized.substring(0, 2);
    }

    return normalized;
}

/**
 * Create popup with weather chart
 */
function createWeatherPopup(weatherData, latlng) {
    // Destroy existing chart if any
    if (currentWeatherChart) {
        currentWeatherChart.destroy();
        currentWeatherChart = null;
    }

    // Create popup content with canvas
    const popupContent = `
        <div class="weather-popup">
            <div class="weather-popup-header">
                <h3>${weatherData.countryName} Climate</h3>
                <p class="weather-subtitle">Monthly Trends (${weatherData.years[0]}-${weatherData.years[weatherData.years.length - 1]})</p>
            </div>
            <div class="weather-chart-tabs">
                <button class="weather-tab active" data-metric="temperature">🌡️ Temperature</button>
                <button class="weather-tab" data-metric="rainfall">🌧️ Rainfall</button>
                <button class="weather-tab" data-metric="solarRadiation">☀️ Solar</button>
            </div>
            <div class="weather-chart-container">
                <canvas id="weatherChart" width="700" height="400"></canvas>
            </div>
        </div>
    `;

    // Create popup
    const popup = L.popup({
        maxWidth: 800,
        minWidth: 700,
        className: 'weather-chart-popup'
    })
    .setLatLng(latlng)
    .setContent(popupContent)
    .openOn(map);

    // Wait for popup to render, then create chart
    setTimeout(() => {
        const canvas = document.getElementById('weatherChart');
        if (canvas) {
            currentWeatherChart = createWeatherChart(canvas, weatherData, 'temperature');

            // Setup tab switching
            setupWeatherTabs(weatherData);
        }
    }, 100);
}

/**
 * Create Chart.js weather chart (area chart with fading colors)
 */
function createWeatherChart(canvas, weatherData, metric) {
    const ctx = canvas.getContext('2d');

    // Prepare datasets - one area per year with fading effect
    // Reverse order so newest year is on top
    const datasets = weatherData.years.slice().reverse().map((year, reverseIndex) => {
        const index = weatherData.years.length - 1 - reverseIndex; // Original index
        const data = getDataForMetric(weatherData, metric, year);
        const color = getYearColor(index, weatherData.years.length);
        const opacity = getYearOpacity(index, weatherData.years.length);

        return {
            label: year.toString(),
            data: data,
            fill: true, // Fill area under line
            backgroundColor: color + opacity, // Fading effect: older = lighter
            borderColor: color, // Full opacity for clear line visibility
            borderWidth: 2,
            tension: 0.4, // Smooth curves
            pointRadius: 0, // No points (cleaner look)
            pointHoverRadius: 5
        };
    });

    const config = {
        type: 'line',
        data: {
            labels: weatherData.months,
            datasets: datasets
        },
        options: {
            responsive: true,
            maintainAspectRatio: true,
            plugins: {
                legend: {
                    display: true,
                    position: 'top',
                    labels: {
                        boxWidth: 20,
                        padding: 10
                    }
                },
                title: {
                    display: true,
                    text: getMetricTitle(metric),
                    font: {
                        size: 16,
                        weight: 'bold'
                    },
                    color: '#005F73'
                },
                tooltip: {
                    mode: 'index',
                    intersect: false,
                    callbacks: {
                        label: function(context) {
                            const label = context.dataset.label || '';
                            const value = context.parsed.y;
                            const unit = getMetricUnit(metric);
                            return `${label}: ${value !== null ? value.toFixed(2) : 'N/A'} ${unit}`;
                        }
                    }
                }
            },
            scales: {
                y: {
                    beginAtZero: metric === 'rainfall', // Start at 0 for rainfall
                    title: {
                        display: true,
                        text: getMetricUnit(metric),
                        color: '#005F73'
                    },
                    grid: {
                        color: 'rgba(0,0,0,0.1)'
                    }
                },
                x: {
                    title: {
                        display: true,
                        text: 'Month',
                        color: '#005F73'
                    },
                    grid: {
                        display: false
                    }
                }
            },
            interaction: {
                mode: 'index',
                intersect: false
            },
            elements: {
                line: {
                    fill: true // Enable area fill
                }
            }
        }
    };

    return new Chart(ctx, config);
}

/**
 * Get data for a specific metric and year
 */
function getDataForMetric(weatherData, metric, year) {
    const metricMap = {
        'temperature': 'temperatureByYear',
        'rainfall': 'rainfallByYear',
        'soilMoisture': 'soilMoistureByYear',
        'solarRadiation': 'solarRadiationByYear'
    };

    const dataKey = metricMap[metric];
    return weatherData[dataKey][year] || [];
}

/**
 * Get color for a year (distinct colors for each year)
 */
function getYearColor(index, total) {
    // Distinct colors that are easy to differentiate
    const colors = [
        '#3498DB', // 2020 - Blue
        '#2ECC71', // 2021 - Green
        '#F39C12', // 2022 - Orange
        '#E74C3C', // 2023 - Red
        '#9B59B6', // 2024 - Purple
        '#1ABC9C'  // 2025 - Teal
    ];

    return colors[index] || '#95A5A6';
}

/**
 * Get opacity for a year (fading effect: old = light, recent = dark)
 */
function getYearOpacity(index, total) {
    // Opacity from 30% (oldest) to 70% (newest) for area fill
    // Lighter fill so colors don't overwhelm
    const opacities = [
        '4D', // 2020 - 30% (very light)
        '59', // 2021 - 35% (light)
        '66', // 2022 - 40% (medium-light)
        '73', // 2023 - 45% (medium)
        '80', // 2024 - 50% (medium-dark)
        'B3'  // 2025 - 70% (more visible)
    ];

    return opacities[index] || '99';
}

/**
 * Get metric title
 */
function getMetricTitle(metric) {
    const titles = {
        'temperature': 'Average Temperature',
        'rainfall': 'Total Rainfall',
        'soilMoisture': 'Soil Moisture',
        'solarRadiation': 'Solar Radiation'
    };
    return titles[metric] || metric;
}

/**
 * Get metric unit
 */
function getMetricUnit(metric) {
    const units = {
        'temperature': '°C',
        'rainfall': 'mm',
        'soilMoisture': 'ratio (0-1)',
        'solarRadiation': 'W/m²'
    };
    return units[metric] || '';
}

/**
 * Setup tab switching for different metrics
 */
function setupWeatherTabs(weatherData) {
    const tabs = document.querySelectorAll('.weather-tab');

    tabs.forEach(tab => {
        tab.addEventListener('click', function() {
            // Update active state
            tabs.forEach(t => t.classList.remove('active'));
            this.classList.add('active');

            // Get metric
            const metric = this.getAttribute('data-metric');

            // Destroy old chart and create new one
            if (currentWeatherChart) {
                currentWeatherChart.destroy();
            }

            const canvas = document.getElementById('weatherChart');
            if (canvas) {
                currentWeatherChart = createWeatherChart(canvas, weatherData, metric);
            }
        });
    });
}

/**
 * Show loading popup while fetching weather data
 */
function showLoadingPopup(countryName, latlng) {
    const content = `
        <div class="weather-popup-error">
            <h3>${countryName}</h3>
            <div class="spinner" style="width: 30px; height: 30px; margin: 20px auto;"></div>
            <p>Loading weather data...</p>
        </div>
    `;

    L.popup()
        .setLatLng(latlng)
        .setContent(content)
        .openOn(map);
}

/**
 * Show popup when no weather data is available
 */
function showNoDataPopup(countryName, latlng) {
    const content = `
        <div class="weather-popup-error">
            <h3>${countryName}</h3>
            <p>No weather data available for this country.</p>
            <p style="font-size: 0.9rem; color: #666;">
                This country may not have coffee origins in the database.
            </p>
        </div>
    `;

    L.popup()
        .setLatLng(latlng)
        .setContent(content)
        .openOn(map);
}

/**
 * Show error popup
 */
function showErrorPopup(countryName, latlng) {
    const content = `
        <div class="weather-popup-error">
            <h3>Error</h3>
            <p>Unable to load weather data for ${countryName}.</p>
        </div>
    `;

    L.popup()
        .setLatLng(latlng)
        .setContent(content)
        .openOn(map);
}
