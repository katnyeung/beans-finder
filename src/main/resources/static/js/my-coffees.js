/**
 * My Coffees page - displays user's tracked coffees
 */

let allTrackings = [];
let currentFilter = '';

/**
 * Initialize page
 */
async function init() {
    // Wait for auth check
    await new Promise(resolve => setTimeout(resolve, 500));

    const user = typeof getCurrentUser === 'function' ? getCurrentUser() : null;

    if (user) {
        document.getElementById('login-prompt').style.display = 'none';
        document.getElementById('my-coffees-content').style.display = 'block';
        setupTabs();
        loadTrackings();
    } else {
        document.getElementById('login-prompt').style.display = 'block';
        document.getElementById('my-coffees-content').style.display = 'none';
    }
}

/**
 * Setup filter tab clicks
 */
function setupTabs() {
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            currentFilter = btn.dataset.status;
            renderTrackings();
        });
    });
}

/**
 * Load user's trackings from API
 */
async function loadTrackings() {
    try {
        const response = await fetch('/api/user/tracking');
        if (response.ok) {
            allTrackings = await response.json();
            renderTrackings();
        } else if (response.status === 401) {
            window.location.href = '/oauth2/authorization/google';
        }
    } catch (e) {
        console.error('Failed to load trackings:', e);
        document.getElementById('coffee-list').innerHTML =
            '<div class="error-state">Failed to load your coffees. Please try again.</div>';
    }
}

/**
 * Render trackings based on current filter
 */
function renderTrackings() {
    const list = document.getElementById('coffee-list');
    const emptyState = document.getElementById('empty-state');

    // Filter trackings
    const filtered = currentFilter
        ? allTrackings.filter(t => t.status === currentFilter.toUpperCase())
        : allTrackings;

    if (filtered.length === 0) {
        list.innerHTML = '';
        emptyState.style.display = 'block';
        return;
    }

    emptyState.style.display = 'none';
    list.innerHTML = filtered.map(t => createTrackingCard(t)).join('');

    // Add remove listeners
    list.querySelectorAll('.remove-tracking').forEach(btn => {
        btn.addEventListener('click', async (e) => {
            e.preventDefault();
            const productId = btn.dataset.productId;
            await removeTracking(productId);
        });
    });

    // Add include-in-chat toggle listeners
    list.querySelectorAll('.include-in-chat-toggle').forEach(checkbox => {
        checkbox.addEventListener('change', async (e) => {
            const productId = checkbox.dataset.productId;
            await toggleIncludeInChat(productId, checkbox.checked);
        });
    });

    // Draw radar charts for products with flavor profiles
    filtered.forEach(t => {
        if (t.flavorProfile && t.flavorProfile.length === 9) {
            drawMiniRadar(`radar-${t.productId}`, t.flavorProfile);
        }
    });
}

/**
 * Create HTML for a tracking card
 */
function createTrackingCard(tracking) {
    const statusIcons = {
        'LOVE': '❤️',
        'BOUGHT': '🛒',
        'WANT': '📌',
        'DISLIKE': '👎'
    };

    const statusLabels = {
        'LOVE': 'Love',
        'BOUGHT': 'Bought',
        'WANT': 'Want to Buy',
        'DISLIKE': "Don't Like"
    };

    const stars = tracking.rating
        ? '⭐'.repeat(tracking.rating) + '☆'.repeat(5 - tracking.rating)
        : '';

    const isIncluded = tracking.includedInChat !== false; // Default true if null

    // Format tasting notes (max 4 for display)
    const tastingNotes = tracking.tastingNotes || [];
    const displayNotes = tastingNotes.slice(0, 4);
    const moreCount = tastingNotes.length - 4;

    // Origin and roast info
    const origin = tracking.origin || '';
    const roastLevel = tracking.roastLevel || '';
    const metaInfo = [origin, roastLevel].filter(Boolean).join(' • ');

    // Check if we have flavor profile for radar
    const hasRadar = tracking.flavorProfile && tracking.flavorProfile.length === 9;

    return `
        <div class="tracking-card">
            <div class="tracking-card-header">
                <span class="tracking-status-badge ${tracking.status.toLowerCase()}">
                    ${statusIcons[tracking.status]} ${statusLabels[tracking.status]}
                </span>
                <button class="remove-tracking" data-product-id="${tracking.productId}" title="Remove">✕</button>
            </div>
            <div class="tracking-card-body">
                <div class="tracking-card-main">
                    <div class="tracking-card-info">
                        <a href="/product-detail.html?id=${tracking.productId}" class="tracking-product-name">
                            ${tracking.productName}
                        </a>
                        <span class="tracking-brand">${tracking.brandName || ''}</span>
                        ${metaInfo ? `<span class="tracking-meta">${metaInfo}</span>` : ''}
                        ${stars ? `<span class="tracking-rating">${stars}</span>` : ''}
                        ${displayNotes.length > 0 ? `
                            <div class="tracking-tasting-notes">
                                ${displayNotes.map(note => `<span class="tasting-note-badge">${note}</span>`).join('')}
                                ${moreCount > 0 ? `<span class="tasting-note-more">+${moreCount}</span>` : ''}
                            </div>
                        ` : ''}
                        ${tracking.notes ? `<p class="tracking-notes">"${tracking.notes}"</p>` : ''}
                    </div>
                    ${hasRadar ? `
                        <div class="tracking-card-radar">
                            <canvas id="radar-${tracking.productId}" width="80" height="80"></canvas>
                        </div>
                    ` : ''}
                </div>
            </div>
            <div class="tracking-card-footer">
                <label class="include-in-chat-label" title="Include this coffee in Chat suggestions">
                    <input type="checkbox" class="include-in-chat-toggle"
                           data-product-id="${tracking.productId}"
                           ${isIncluded ? 'checked' : ''}>
                    <span>Use in Chat</span>
                </label>
                <span class="tracking-date">Updated ${formatDate(tracking.updatedAt)}</span>
            </div>
        </div>
    `;
}

/**
 * Remove tracking
 */
async function removeTracking(productId) {
    if (!confirm('Remove this coffee from your list?')) return;

    try {
        const response = await fetch(`/api/user/tracking/${productId}`, {
            method: 'DELETE'
        });

        if (response.ok) {
            allTrackings = allTrackings.filter(t => t.productId != productId);
            renderTrackings();
        }
    } catch (e) {
        console.error('Failed to remove tracking:', e);
    }
}

/**
 * Toggle include-in-chat flag
 */
async function toggleIncludeInChat(productId, isIncluded) {
    try {
        const response = await fetch(`/api/user/tracking/${productId}/include-in-chat`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ includedInChat: isIncluded })
        });

        if (response.ok) {
            // Update local state
            const tracking = allTrackings.find(t => t.productId == productId);
            if (tracking) {
                tracking.includedInChat = isIncluded;
            }
            console.log(`Product ${productId} includedInChat set to ${isIncluded}`);
        }
    } catch (e) {
        console.error('Failed to toggle include-in-chat:', e);
    }
}

/**
 * Format date for display
 */
function formatDate(dateStr) {
    if (!dateStr) return '';
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = now - date;
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

    if (diffDays === 0) return 'today';
    if (diffDays === 1) return 'yesterday';
    if (diffDays < 7) return `${diffDays} days ago`;
    if (diffDays < 30) return `${Math.floor(diffDays / 7)} weeks ago`;

    return date.toLocaleDateString('en-GB', {
        day: 'numeric',
        month: 'short',
        year: date.getFullYear() !== now.getFullYear() ? 'numeric' : undefined
    });
}

/**
 * Draw a mini radar chart on a canvas
 * @param {string} canvasId - Canvas element ID
 * @param {number[]} values - 9-dimensional flavor profile [fruity, floral, sweet, nutty, spices, roasted, green, sour, other]
 */
function drawMiniRadar(canvasId, values) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    const centerX = canvas.width / 2;
    const centerY = canvas.height / 2;
    const radius = Math.min(centerX, centerY) - 5;

    // SCA category labels (abbreviated for mini display)
    const labels = ['Fr', 'Fl', 'Sw', 'Nu', 'Sp', 'Ro', 'Gr', 'So', 'Ot'];
    const colors = [
        '#e74c3c', // fruity - red
        '#9b59b6', // floral - purple
        '#f39c12', // sweet - orange
        '#8b4513', // nutty - brown
        '#e67e22', // spices - dark orange
        '#2c3e50', // roasted - dark
        '#27ae60', // green - green
        '#f1c40f', // sour - yellow
        '#95a5a6'  // other - gray
    ];

    const numAxes = 9;
    const angleStep = (2 * Math.PI) / numAxes;
    const startAngle = -Math.PI / 2; // Start from top

    // Clear canvas
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Draw background circles (2 levels)
    ctx.strokeStyle = '#e0e0e0';
    ctx.lineWidth = 0.5;
    [0.5, 1].forEach(level => {
        ctx.beginPath();
        ctx.arc(centerX, centerY, radius * level, 0, 2 * Math.PI);
        ctx.stroke();
    });

    // Draw axes
    ctx.strokeStyle = '#d0d0d0';
    ctx.lineWidth = 0.5;
    for (let i = 0; i < numAxes; i++) {
        const angle = startAngle + i * angleStep;
        const x = centerX + radius * Math.cos(angle);
        const y = centerY + radius * Math.sin(angle);
        ctx.beginPath();
        ctx.moveTo(centerX, centerY);
        ctx.lineTo(x, y);
        ctx.stroke();
    }

    // Draw data polygon (filled)
    ctx.beginPath();
    for (let i = 0; i < numAxes; i++) {
        const angle = startAngle + i * angleStep;
        const value = Math.min(values[i] || 0, 1); // Clamp to 0-1
        const x = centerX + radius * value * Math.cos(angle);
        const y = centerY + radius * value * Math.sin(angle);
        if (i === 0) {
            ctx.moveTo(x, y);
        } else {
            ctx.lineTo(x, y);
        }
    }
    ctx.closePath();
    ctx.fillStyle = 'rgba(10, 147, 150, 0.3)';
    ctx.fill();
    ctx.strokeStyle = '#0A9396';
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // Draw data points with category colors
    for (let i = 0; i < numAxes; i++) {
        const angle = startAngle + i * angleStep;
        const value = Math.min(values[i] || 0, 1);
        if (value > 0.1) { // Only draw visible points
            const x = centerX + radius * value * Math.cos(angle);
            const y = centerY + radius * value * Math.sin(angle);
            ctx.beginPath();
            ctx.arc(x, y, 2.5, 0, 2 * Math.PI);
            ctx.fillStyle = colors[i];
            ctx.fill();
        }
    }
}

// Initialize
document.addEventListener('DOMContentLoaded', init);
