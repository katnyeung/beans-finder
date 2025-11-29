// Product Detail Page JavaScript
let currentProduct = null;
let productMap = null;

// SCA Category Colors (matching flavor-wheel.js)
const SCA_COLORS = {
    fruity: '#FF6B6B',
    floral: '#FFB6C1',
    sweet: '#FFD93D',
    nutty: '#A0522D',
    spices: '#FF6347',
    roasted: '#8B4513',
    green: '#90EE90',
    sour: '#FFA07A',
    other: '#B0B0B0'
};

// Get product ID from URL
function getProductId() {
    const params = new URLSearchParams(window.location.search);
    return params.get('id');
}

// Load product data
async function loadProduct() {
    const productId = getProductId();

    if (!productId) {
        showError('No product ID specified');
        return;
    }

    try {
        const response = await fetch(`/api/products/${productId}`);

        if (!response.ok) {
            throw new Error('Product not found');
        }

        currentProduct = await response.json();
        displayProduct(currentProduct);
        loadRelatedProducts(productId);

        // Log product view for analytics
        logProductView(currentProduct.id, currentProduct.brandId);

    } catch (error) {
        console.error('Error loading product:', error);
        showError('Failed to load product: ' + error.message);
    }
}

// Display product information
function displayProduct(product) {
    document.getElementById('loading').style.display = 'none';
    document.getElementById('product-detail-container').style.display = 'block';

    // Hero section
    document.getElementById('product-name').textContent = product.productName;
    document.getElementById('product-name-breadcrumb').textContent = product.productName;

    // Brand link
    const brandLink = document.getElementById('brand-link');
    if (product.brandId && product.brandName) {
        brandLink.textContent = product.brandName;
        brandLink.href = `/products.html?brandId=${product.brandId}`;
    } else {
        brandLink.style.display = 'none';
        document.querySelectorAll('.breadcrumb-separator')[0].style.display = 'none';
    }

    // Seller URL button - use redirect page for tracking
    const sellerBtn = document.getElementById('seller-url-btn');
    if (product.sellerUrl) {
        sellerBtn.href = `/redirect.html?id=${product.id}`;
    } else {
        sellerBtn.style.display = 'none';
    }

    // Product details
    document.getElementById('detail-brand').textContent = product.brandName || 'Unknown';
    document.getElementById('detail-brand').onclick = () => {
        if (product.brandId) {
            window.location.href = `/products.html?brandId=${product.brandId}`;
        }
    };
    document.getElementById('detail-brand').style.cursor = product.brandId ? 'pointer' : 'default';
    document.getElementById('detail-brand').style.color = product.brandId ? '#005F73' : '#333';
    // Display price with variants if available (hide null prices)
    const priceElement = document.getElementById('detail-price');
    if (product.priceVariantsJson) {
        try {
            const variants = typeof product.priceVariantsJson === 'string'
                ? JSON.parse(product.priceVariantsJson)
                : product.priceVariantsJson;

            if (Array.isArray(variants) && variants.length > 0) {
                const variantText = variants
                    .map(v => v.price != null
                        ? `${v.size}: ${product.currency || 'GBP'} ${v.price}`
                        : v.size)
                    .join(' | ');
                priceElement.textContent = variantText;
            } else if (product.price) {
                priceElement.textContent = `${product.currency || 'GBP'} ${product.price}`;
            } else {
                priceElement.textContent = 'N/A';
            }
        } catch (e) {
            priceElement.textContent = product.price
                ? `${product.currency || 'GBP'} ${product.price}`
                : 'N/A';
        }
    } else {
        priceElement.textContent = product.price
            ? `${product.currency || 'GBP'} ${product.price}`
            : 'N/A';
    }
    document.getElementById('detail-origin').textContent = product.origin || 'N/A';
    document.getElementById('detail-region').textContent = product.region || 'N/A';
    document.getElementById('detail-roast').textContent = product.roastLevel || 'Unknown';
    document.getElementById('detail-process').textContent = product.process || 'N/A';
    document.getElementById('detail-variety').textContent = product.variety || 'N/A';
    document.getElementById('detail-altitude').textContent = product.altitude || 'N/A';
    document.getElementById('detail-producer').textContent = product.producer || 'N/A';

    const stockSpan = document.getElementById('detail-stock');
    stockSpan.textContent = product.inStock ? '✅ In Stock' : '❌ Out of Stock';
    stockSpan.className = 'detail-value ' + (product.inStock ? 'in-stock' : 'out-of-stock');

    // Merged Flavor Profile & Tasting Notes
    const flavorBadges = document.getElementById('flavor-badges');
    flavorBadges.innerHTML = '';
    let hasAnyFlavors = false;

    // First, add all tasting notes (these are the raw extracted flavors)
    if (product.tastingNotesJson) {
        try {
            const tastingNotes = typeof product.tastingNotesJson === 'string'
                ? JSON.parse(product.tastingNotesJson)
                : product.tastingNotesJson;

            if (Array.isArray(tastingNotes) && tastingNotes.length > 0) {
                tastingNotes.forEach(note => {
                    if (note && note.trim()) {
                        const badge = document.createElement('span');
                        badge.className = 'tasting-note-item';
                        badge.textContent = note;
                        flavorBadges.appendChild(badge);
                        hasAnyFlavors = true;
                    }
                });
            }
        } catch (e) {
            console.error('Error parsing tasting notes:', e);
        }
    }

    if (hasAnyFlavors) {
        document.getElementById('flavor-card').style.display = 'block';
    }

    // Description - format as short list
    if (product.rawDescription) {
        const descDiv = document.getElementById('product-description');
        descDiv.innerHTML = '';

        // Split by common sentence delimiters and clean up
        const sentences = product.rawDescription
            .split(/[.!?]\s+/)
            .map(s => s.trim())
            .filter(s => s.length > 10); // Filter out very short fragments

        if (sentences.length > 0) {
            const ul = document.createElement('ul');
            ul.className = 'description-list';

            sentences.forEach(sentence => {
                const li = document.createElement('li');
                li.textContent = sentence.endsWith('.') || sentence.endsWith('!') || sentence.endsWith('?')
                    ? sentence
                    : sentence + '.';
                ul.appendChild(li);
            });

            descDiv.appendChild(ul);
            document.getElementById('description-card').style.display = 'block';
        }
    }

    // Initialize map
    initializeMap(product);

    // Chatbot button - navigate to chat page with prefilled product details
    document.getElementById('ask-chatbot-btn').onclick = () => {
        const params = new URLSearchParams();
        params.set('chatbotProductId', product.id);
        params.set('chatbotProductName', product.productName);
        if (product.brandId) params.set('brandId', product.brandId);
        if (product.brandName) params.set('brandName', product.brandName);
        if (product.origin) params.set('origin', product.origin);
        if (product.region) params.set('region', product.region);
        if (product.roastLevel) params.set('roastLevel', product.roastLevel);
        if (product.process) params.set('process', product.process);
        if (product.price) params.set('price', product.price);
        if (product.currency) params.set('currency', product.currency || 'GBP');

        // Pass tasting notes as comma-separated
        if (product.tastingNotesJson) {
            try {
                const notes = typeof product.tastingNotesJson === 'string'
                    ? JSON.parse(product.tastingNotesJson)
                    : product.tastingNotesJson;
                if (Array.isArray(notes) && notes.length > 0) {
                    params.set('tastingNotes', notes.join(','));
                }
            } catch (e) { /* ignore */ }
        }

        window.location.href = `/chat.html?${params.toString()}`;
    };

    // Request Update button (flags product for admin review)
    document.getElementById('request-update-btn').onclick = async () => {
        const btn = document.getElementById('request-update-btn');
        const productId = getProductId();

        // Disable button and show loading state
        btn.disabled = true;
        btn.textContent = '⏳ Submitting...';

        try {
            const response = await fetch(`/api/products/${productId}/request-update`, {
                method: 'POST'
            });

            const message = await response.text();

            if (response.ok) {
                btn.textContent = '✅ Update Requested';
                btn.style.backgroundColor = '#27ae60';
            } else {
                alert('❌ Failed to request update:\n' + message);
                btn.disabled = false;
                btn.textContent = '🔄 Request Update';
            }
        } catch (error) {
            console.error('Error requesting update:', error);
            alert('❌ Failed to request update: ' + error.message);
            btn.disabled = false;
            btn.textContent = '🔄 Request Update';
        }
    };
}

// Initialize mini map
function initializeMap(product) {
    // Default to center of world if no coordinates
    let latitude = 0;
    let longitude = 0;
    let hasCoordinates = false;

    // Try to get coordinates from origin (via geolocation service)
    // For now, we'll use a simple country-to-coordinates mapping
    const countryCoordinates = {
        'Ethiopia': [9.145, 40.489],
        'Colombia': [4.571, -74.297],
        'Brazil': [-14.235, -51.925],
        'Kenya': [-0.023, 37.906],
        'Costa Rica': [9.748, -83.753],
        'Guatemala': [15.783, -90.231],
        'Honduras': [15.200, -86.241],
        'Panama': [8.538, -80.782],
        'Peru': [-9.190, -75.015],
        'Rwanda': [-1.940, 29.874],
        'Burundi': [-3.373, 29.919],
        'Tanzania': [-6.369, 34.888],
        'Uganda': [1.373, 32.290],
        'Yemen': [15.552, 48.516],
        'Indonesia': [-0.789, 113.921],
        'Papua New Guinea': [-6.315, 143.956],
        'India': [20.593, 78.962],
        'Vietnam': [14.058, 108.277],
        'China': [35.861, 104.195],
        'Mexico': [23.634, -102.552],
        'El Salvador': [13.794, -88.896],
        'Nicaragua': [12.865, -85.207]
    };

    if (product.origin && countryCoordinates[product.origin]) {
        [latitude, longitude] = countryCoordinates[product.origin];
        hasCoordinates = true;
    }

    // Initialize Leaflet map
    const mapContainer = document.getElementById('product-map');
    productMap = L.map(mapContainer).setView([latitude, longitude], hasCoordinates ? 6 : 2);

    // Add tile layer
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© OpenStreetMap contributors',
        maxZoom: 18
    }).addTo(productMap);

    // Add marker if we have coordinates
    if (hasCoordinates) {
        const marker = L.marker([latitude, longitude]).addTo(productMap);
        marker.bindPopup(`
            <strong>${product.origin}</strong><br>
            ${product.region || 'No specific region'}
        `).openPopup();
    }

    // Fix map rendering issue
    setTimeout(() => {
        productMap.invalidateSize();
    }, 100);
}

// Load related products
async function loadRelatedProducts(productId) {
    try {
        const response = await fetch(`/api/products/${productId}/related?limit=6`);

        if (!response.ok) {
            console.error('Failed to load related products');
            return;
        }

        const relatedProducts = await response.json();
        displayRelatedProducts(relatedProducts);

    } catch (error) {
        console.error('Error loading related products:', error);
    }
}

// Display related products
function displayRelatedProducts(products) {
    const container = document.getElementById('related-products');
    const brandName = document.getElementById('related-brand-name');

    if (!products || products.length === 0) {
        container.innerHTML = '<p class="no-results">No related products found</p>';
        return;
    }

    brandName.textContent = currentProduct?.brandName || 'this brand';
    container.innerHTML = '';

    products.forEach(product => {
        const productCard = document.createElement('div');
        productCard.className = 'related-product-card';

        // Format price with variants fallback (hide null prices)
        let priceText = '';
        if (product.priceVariantsJson) {
            try {
                const variants = typeof product.priceVariantsJson === 'string'
                    ? JSON.parse(product.priceVariantsJson)
                    : product.priceVariantsJson;
                if (Array.isArray(variants) && variants.length > 0) {
                    priceText = variants
                        .map(v => v.price != null
                            ? `${v.size}: ${product.currency || 'GBP'} ${v.price}`
                            : v.size)
                        .join(' | ');
                }
            } catch (e) {
                console.error('Error parsing price variants:', e);
            }
        }
        // Fallback to single price if no variants
        if (!priceText && product.price) {
            priceText = `${product.currency || 'GBP'} ${product.price}`;
        }

        productCard.innerHTML = `
            <div class="related-product-info">
                <a href="/product-detail.html?id=${product.id}" class="related-product-name">${product.productName}</a>
                <div class="related-product-meta">
                    <span>${product.origin || 'Unknown Origin'}</span>
                    ${priceText ? `<span class="related-product-price">${priceText}</span>` : ''}
                </div>
            </div>
        `;

        container.appendChild(productCard);
    });
}

// Show error message
function showError(message) {
    document.getElementById('loading').style.display = 'none';
    const errorDiv = document.getElementById('error');
    errorDiv.textContent = message;
    errorDiv.style.display = 'block';
}

// Log product view for analytics
async function logProductView(productId, brandId) {
    try {
        await fetch('/api/analytics/log', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                actionType: 'product_view',
                productId: productId,
                brandId: brandId
            })
        });
    } catch (e) {
        // Silent fail - don't break page if analytics fails
        console.debug('Analytics log failed:', e);
    }
}

// Initialize page
document.addEventListener('DOMContentLoaded', () => {
    loadProduct();
});
