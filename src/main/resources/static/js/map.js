// Coffee Origins Map with Leaflet.js
let map;
let brandMarkers = [];
let originMarkers = [];
let producerMarkers = [];
let countryBoundaries = null;
let mapData = null;

// Storage for mappings
let brandToOrigins = {}; // Maps brand ID to related origin markers
let originToBrands = {}; // Maps origin coords to brand IDs
let brandColors = {}; // Maps brand ID to unique color
let countryToOrigins = {}; // Maps country name to origin markers
let countryNameToCode = {}; // Maps country names to ISO codes

// State tracking
let currentlySelectedBrand = null;
let currentlyHoveredCountry = null;

// Filter states
let showBrands = true;
let showOrigins = true;
let showProducers = true;

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
    document.getElementById('filter-brands').addEventListener('click', function() {
        showBrands = !showBrands;
        this.classList.toggle('active');
        updateMapLayers();
    });

    document.getElementById('filter-origins').addEventListener('click', function() {
        showOrigins = !showOrigins;
        this.classList.toggle('active');
        updateMapLayers();
    });

    document.getElementById('filter-producers').addEventListener('click', function() {
        showProducers = !showProducers;
        this.classList.toggle('active');
        updateMapLayers();
    });

    // Remove the connections button handler as we no longer use connection lines
    const connectionsBtn = document.getElementById('filter-connections');
    if (connectionsBtn) {
        connectionsBtn.style.display = 'none'; // Hide the connections toggle
    }
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
        const coordKey = `${originData.data.latitude},${originData.data.longitude}`;

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
        const response = await fetch('/api/map/data');
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
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
            // Store the origin coordinates as a unique identifier
            brandToOrigins[conn.fromId].add(`${conn.toLat},${conn.toLon}`);
        }
    });
}

function buildOriginToBrandsMapping() {
    originToBrands = {};

    if (!mapData.connections) return;

    // Build reverse mapping: origin -> brands
    mapData.connections.forEach(conn => {
        if (conn.type === 'brand-origin' && conn.fromId) {
            const coordKey = `${conn.toLat},${conn.toLon}`;
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
        riseOnHover: true
    });

    // Store original properties for state management
    marker.brandId = brand.id;
    marker.brandColor = brandColor;

    const popupContent = `
        <div class="popup-title">☕ ${brand.name}</div>
        <div class="popup-info">
            <strong>Country:</strong> ${brand.country || 'Unknown'}<br>
            <strong>Products:</strong> ${brand.productCount}<br>
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

    marker.on('click', function() {
        // Toggle selection
        if (currentlySelectedBrand === brand.id) {
            currentlySelectedBrand = null;
            resetOriginColors();
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
        const coordKey = `${originData.data.latitude},${originData.data.longitude}`;
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

function createOriginMarker(origin) {
    const coordKey = `${origin.latitude},${origin.longitude}`;
    const defaultColor = getOriginDefaultColor(coordKey);

    const marker = L.circleMarker([origin.latitude, origin.longitude], {
        radius: 6,
        fillColor: defaultColor,
        color: '#2F4F2F',
        weight: 2,
        opacity: 1,
        fillOpacity: 0.8,
        className: 'origin-marker'
    });

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
    content += `<h3 style="color: #6B4423; margin-bottom: 10px;">☕ Products from ${origin.region || origin.country}</h3>`;
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

        // Product name
        if (productUrl !== '#') {
            content += `<a href="${productUrl}" target="_blank" style="color: #6B4423; text-decoration: none; font-weight: 500; display: block;">${productName}</a>`;
        } else {
            content += `<div style="color: #6B4423; font-weight: 500;">${productName}</div>`;
        }

        // Product details
        content += `<div style="margin-top: 4px;">`;
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
    if (!mapData.brands) return '#6B4423';

    const brand = mapData.brands.find(b => b.name === brandName);
    if (brand && brandColors[brand.id]) {
        return brandColors[brand.id];
    }

    // Fallback color
    return '#6B4423';
}

function createProducerMarker(producer) {
    const marker = L.circleMarker([producer.latitude, producer.longitude], {
        radius: 5,
        fillColor: '#CD853F',
        color: '#8B4513',
        weight: 2,
        opacity: 1,
        fillOpacity: 0.8,
        className: 'producer-marker'
    });

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
