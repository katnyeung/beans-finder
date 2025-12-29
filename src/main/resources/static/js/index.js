/**
 * Homepage trending functionality
 * Visual box-based layout with per-section period switching
 */

let trendingData = null;

// Track current period for each section independently
const currentPeriods = {
    products: 'week',
    flavors: 'week'
};

document.addEventListener('DOMContentLoaded', () => {
    loadTrendingData();
    loadHotDeals();
    loadMarketPulse();
    setupPeriodSwitchers();
});

/**
 * Setup period switcher click handlers for each section
 */
function setupPeriodSwitchers() {
    document.querySelectorAll('.period-switcher').forEach(switcher => {
        const target = switcher.dataset.target;

        switcher.querySelectorAll('.period-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                // Update active state within this switcher only
                switcher.querySelectorAll('.period-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');

                // Update period for this section
                currentPeriods[target] = btn.dataset.period;

                // Re-render just this section
                if (trendingData) {
                    renderSection(target, trendingData[currentPeriods[target]]);
                }
            });
        });
    });
}

/**
 * Load trending data from static JSON cache
 */
async function loadTrendingData() {
    try {
        const response = await fetch('/cache/trending-data.json');
        if (!response.ok) {
            throw new Error('Failed to load trending data');
        }

        trendingData = await response.json();

        // Render new arrivals - show newest products from 'all'
        renderNewProducts(trendingData.all);

        // Render flavors with default period (week) - Top Origins removed
        renderSection('flavors', trendingData.week);

    } catch (error) {
        console.error('Error loading trending data:', error);
        showLoadingError();
    }
}

/**
 * Render a specific section (flavors only - products handled separately, origins removed)
 */
function renderSection(section, data) {
    if (!data) return;

    switch (section) {
        case 'flavors':
            renderTopFlavors(data);
            break;
    }
}

/**
 * Render new products as horizontal scrollable boxes
 * @param data - product data from 'all' period (shows newest products overall)
 */
function renderNewProducts(data) {
    const container = document.getElementById('new-products-boxes');

    if (!container) return;

    if (!data.newProducts || data.newProducts.length === 0) {
        container.innerHTML = '<div class="empty-state">No new products</div>';
        return;
    }

    // Limit to 5 products
    const productsToShow = data.newProducts.slice(0, 5);

    container.innerHTML = productsToShow.map(p => `
        <a href="/product-detail.html?id=${p.id}" class="trend-box product-box">
            <div class="trend-box-name">${escapeHtml(p.productName)}</div>
            <div class="trend-box-meta">${escapeHtml(p.brandName || '')}</div>
            ${p.origin ? `<div class="trend-box-origin">${escapeHtml(p.origin)}</div>` : ''}
        </a>
    `).join('');
}

/**
 * Render top flavors as sized boxes (limited to 6 items)
 */
function renderTopFlavors(data) {
    const container = document.getElementById('top-flavors-boxes');
    if (!container) return;

    if (!data.topFlavors || data.topFlavors.length === 0) {
        container.innerHTML = '<div class="empty-state">No flavor data</div>';
        return;
    }

    // Limit to 6 flavors
    const flavorsToShow = data.topFlavors.slice(0, 6);

    // Find max count for sizing
    const maxCount = Math.max(...flavorsToShow.map(f => f.productCount));

    container.innerHTML = flavorsToShow.map(f => {
        const sizeClass = getBoxSize(f.productCount, maxCount);
        const rankBadge = getRankChangeBadge(f.rankChange);
        return `
            <a href="/discover.html?flavor=${encodeURIComponent(f.flavor)}"
               class="trend-box flavor-box ${sizeClass}">
                <div class="trend-box-name">${escapeHtml(f.flavor)}${rankBadge}</div>
                <div class="trend-box-count">${f.productCount}</div>
            </a>
        `;
    }).join('');
}

/**
 * Get box size class based on count relative to max
 */
function getBoxSize(count, maxCount) {
    const ratio = count / maxCount;
    if (ratio >= 0.8) return 'box-xl';
    if (ratio >= 0.5) return 'box-lg';
    if (ratio >= 0.3) return 'box-md';
    return 'box-sm';
}

/**
 * Get rank change badge HTML
 * @param rankChange positive = moved up, negative = moved down, null = new/no data
 */
function getRankChangeBadge(rankChange) {
    if (rankChange === null || rankChange === undefined) {
        return ''; // No badge for new entries or "all" period
    }
    if (rankChange > 0) {
        return `<span class="rank-badge rank-up">▲${rankChange}</span>`;
    }
    if (rankChange < 0) {
        return `<span class="rank-badge rank-down">▼${Math.abs(rankChange)}</span>`;
    }
    return '<span class="rank-badge rank-same">―</span>'; // No change
}

/**
 * Show loading error message
 */
function showLoadingError() {
    const containers = ['new-products-boxes', 'top-flavors-boxes'];
    containers.forEach(id => {
        const el = document.getElementById(id);
        if (el) {
            el.innerHTML = '<div class="error-state">Failed to load</div>';
        }
    });
}

/**
 * Load hot deals from API
 */
async function loadHotDeals() {
    const container = document.getElementById('hot-deals-boxes');

    if (!container) return;

    try {
        const response = await fetch('/api/discounts');
        if (!response.ok) {
            throw new Error('Failed to load deals');
        }

        const deals = await response.json();

        if (deals.length === 0) {
            container.innerHTML = '<div class="empty-state">No deals available</div>';
            return;
        }

        // Shuffle deals randomly, then pick 5
        const shuffled = deals.sort(() => Math.random() - 0.5);
        const dealsToShow = shuffled.slice(0, 5);

        container.innerHTML = dealsToShow.map(deal => `
            <a href="${deal.brandWebsite || '/discounts.html'}" target="${deal.brandWebsite ? '_blank' : '_self'}"
               rel="noopener" class="trend-box deal-box">
                <div class="trend-box-name">${escapeHtml(deal.title)}</div>
                <div class="trend-box-meta">${escapeHtml(deal.brandName || '')}</div>
                ${deal.discountCode ? `<div class="deal-code">${escapeHtml(deal.discountCode)}</div>` : ''}
            </a>
        `).join('');

    } catch (error) {
        console.error('Error loading deals:', error);
        container.innerHTML = '<div class="empty-state">Failed to load deals</div>';
    }
}

/**
 * Escape HTML to prevent XSS
 */
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Load market pulse data from API
 */
async function loadMarketPulse() {
    const container = document.getElementById('market-pulse-container');
    if (!container) return;

    try {
        const response = await fetch('/api/digest/market-pulse');
        if (!response.ok) {
            container.innerHTML = '';
            return;
        }

        const data = await response.json();
        renderMarketPulse(container, data);

    } catch (error) {
        console.error('Error loading market pulse:', error);
        container.innerHTML = '';
    }
}

/**
 * Render market pulse widget
 */
function renderMarketPulse(container, data) {
    const originsHtml = data.trendingOrigins && data.trendingOrigins.length > 0
        ? data.trendingOrigins.map(o => `<span class="pulse-item">${escapeHtml(o.name)}</span>`).join('')
        : '<span class="pulse-item">-</span>';

    const processesHtml = data.trendingProcesses && data.trendingProcesses.length > 0
        ? data.trendingProcesses.map(p => `<span class="pulse-item">${escapeHtml(p.name)}</span>`).join('')
        : '<span class="pulse-item">-</span>';

    container.innerHTML = `
        <div class="pulse-grid">
            <div class="pulse-card">
                <span class="pulse-number">${data.totalProducts || 0}</span>
                <span class="pulse-label">Total Products</span>
            </div>
            <div class="pulse-card">
                <span class="pulse-number">+${data.newProductsThisWeek || 0}</span>
                <span class="pulse-label">New This Week</span>
            </div>
            <div class="pulse-card">
                <span class="pulse-label">Top Origins</span>
                <div class="pulse-items">${originsHtml}</div>
            </div>
            <div class="pulse-card">
                <span class="pulse-label">Top Processes</span>
                <div class="pulse-items">${processesHtml}</div>
            </div>
        </div>
        <a href="${data.digestUrl || '/insights.html'}" class="view-digest-link">View This Week's Insights →</a>
    `;
}
