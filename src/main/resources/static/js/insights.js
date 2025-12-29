// Digest page functionality

document.addEventListener('DOMContentLoaded', () => {
    loadArchiveSidebar();

    // Check for week parameter in URL
    const urlParams = new URLSearchParams(window.location.search);
    const weekParam = urlParams.get('week');

    if (weekParam) {
        loadDigestByWeek(weekParam);
    } else {
        loadLatestDigest();
    }
});

/**
 * Load archive list for sidebar, grouped by month quarter
 */
async function loadArchiveSidebar() {
    const container = document.getElementById('digest-archive-list');

    try {
        const response = await fetch('/api/digest/archive');
        if (!response.ok) {
            container.innerHTML = '<p class="empty-text">No archives yet</p>';
            return;
        }

        const digests = await response.json();

        if (digests.length === 0) {
            container.innerHTML = '<p class="empty-text">No archives yet</p>';
            return;
        }

        // Group by month and quarter
        const grouped = groupByMonthQuarter(digests);

        container.innerHTML = renderArchiveGroups(grouped);

    } catch (error) {
        console.error('Error loading archive:', error);
        container.innerHTML = '<p class="empty-text">Failed to load</p>';
    }
}

/**
 * Group digests by month and quarter (1st, 2nd, 3rd, 4th week)
 */
function groupByMonthQuarter(digests) {
    const groups = {};

    digests.forEach(d => {
        const date = new Date(d.weekStart);
        const monthYear = date.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' });
        const day = date.getDate();

        // Determine quarter of month
        let quarter;
        if (day <= 7) quarter = '1st week';
        else if (day <= 14) quarter = '2nd week';
        else if (day <= 21) quarter = '3rd week';
        else quarter = '4th week';

        if (!groups[monthYear]) {
            groups[monthYear] = [];
        }

        groups[monthYear].push({
            ...d,
            quarter,
            label: `${monthYear} - ${quarter}`
        });
    });

    return groups;
}

/**
 * Render archive groups as HTML
 */
function renderArchiveGroups(grouped) {
    let html = '';

    // Get current week param to highlight active
    const urlParams = new URLSearchParams(window.location.search);
    const currentWeek = urlParams.get('week');

    for (const [monthYear, digests] of Object.entries(grouped)) {
        html += `<div class="archive-month">
            <h4>${monthYear}</h4>
            <ul class="archive-links">`;

        digests.forEach(d => {
            const isActive = currentWeek === d.weekStart || (!currentWeek && digests[0] === d);
            html += `<li>
                <a href="?week=${d.weekStart}" class="${isActive ? 'active' : ''}">${d.quarter}</a>
            </li>`;
        });

        html += `</ul></div>`;
    }

    return html;
}

async function loadLatestDigest() {
    const loading = document.getElementById('loading');
    const noDigest = document.getElementById('no-digest');
    const content = document.getElementById('digest-content');

    try {
        const response = await fetch('/api/digest/latest');

        if (!response.ok) {
            loading.classList.add('hidden');
            noDigest.classList.remove('hidden');
            return;
        }

        const digest = await response.json();

        loading.classList.add('hidden');
        content.classList.remove('hidden');

        renderDigest(digest);

    } catch (error) {
        console.error('Error loading digest:', error);
        loading.classList.add('hidden');
        noDigest.classList.remove('hidden');
    }
}

async function loadDigestByWeek(weekStart) {
    const loading = document.getElementById('loading');
    const noDigest = document.getElementById('no-digest');
    const content = document.getElementById('digest-content');

    try {
        const response = await fetch(`/api/digest/${weekStart}`);

        if (!response.ok) {
            loading.classList.add('hidden');
            noDigest.classList.remove('hidden');
            return;
        }

        const digest = await response.json();

        loading.classList.add('hidden');
        content.classList.remove('hidden');

        renderDigest(digest);

    } catch (error) {
        console.error('Error loading digest:', error);
        loading.classList.add('hidden');
        noDigest.classList.remove('hidden');
    }
}

function renderDigest(digest) {
    // Set date range
    const dateEl = document.getElementById('digest-date');
    dateEl.textContent = `Week of ${formatDate(digest.weekStart)} - ${formatDate(digest.weekEnd)}`;

    // Render narrative in magazine layout
    renderNarrativeMagazine(digest.narrative);

    // Render origin photos
    renderOriginPhotos(digest.metrics);

    // Render compact sources
    renderSources(digest.newsArticles);

    // Render data insights
    renderInsights(digest.metrics);
}

/**
 * Render origin photos for top origins
 */
function renderOriginPhotos(metrics) {
    const container = document.getElementById('origin-photos');
    if (!container) return;

    // Get top 3 origins
    if (!metrics || !metrics.originActivity || !metrics.originActivity.topOrigins) {
        container.innerHTML = '';
        return;
    }

    const topOrigins = metrics.originActivity.topOrigins.slice(0, 3);

    // Origin to image filename mapping (lowercase, no spaces)
    const originImages = {
        'ethiopia': { file: 'ethiopia.jpg', caption: 'Ethiopian coffee farm in the highlands' },
        'colombia': { file: 'colombia.jpg', caption: 'Colombian coffee plantation' },
        'brazil': { file: 'brazil.jpg', caption: 'Brazilian coffee harvest' },
        'kenya': { file: 'kenya.jpg', caption: 'Kenyan coffee cherries' },
        'guatemala': { file: 'guatemala.jpg', caption: 'Guatemalan highland farm' },
        'costa rica': { file: 'costa-rica.jpg', caption: 'Costa Rican coffee estate' },
        'honduras': { file: 'honduras.jpg', caption: 'Honduran coffee mountains' },
        'peru': { file: 'peru.jpg', caption: 'Peruvian coffee terraces' },
        'rwanda': { file: 'rwanda.jpg', caption: 'Rwandan coffee hills' },
        'burundi': { file: 'burundi.jpg', caption: 'Burundian coffee farm' },
        'indonesia': { file: 'indonesia.jpg', caption: 'Indonesian coffee forest' },
        'yemen': { file: 'yemen.jpg', caption: 'Yemeni traditional coffee' },
        'panama': { file: 'panama.jpg', caption: 'Panamanian Gesha farm' }
    };

    // Filter origins that have images
    const originsWithImages = topOrigins.filter(o =>
        originImages[o.origin.toLowerCase()]
    );

    if (originsWithImages.length === 0) {
        container.innerHTML = '';
        return;
    }

    container.innerHTML = `
        <h3 class="origin-photos-title">🌍 This Week's Top Origins</h3>
        <div class="origin-photos-grid">
            ${originsWithImages.map(o => {
                const origin = o.origin.toLowerCase();
                const img = originImages[origin];
                const flag = getOriginFlag(o.origin);
                return `
                    <a href="/filter-products.html?origin=${encodeURIComponent(o.origin)}" class="origin-photo-card">
                        <div class="origin-photo-wrapper">
                            <img src="/images/origins/${img.file}" alt="${o.origin}"
                                 onerror="this.parentElement.parentElement.style.display='none'">
                        </div>
                        <div class="origin-photo-info">
                            <span class="origin-photo-name">${flag} ${o.origin}</span>
                            <span class="origin-photo-count">${o.count} coffees</span>
                        </div>
                    </a>
                `;
            }).join('')}
        </div>
    `;
}

/**
 * Get flag emoji for origin
 */
function getOriginFlag(origin) {
    const flags = {
        'Ethiopia': '🇪🇹', 'Colombia': '🇨🇴', 'Brazil': '🇧🇷', 'Kenya': '🇰🇪',
        'Guatemala': '🇬🇹', 'Costa Rica': '🇨🇷', 'Honduras': '🇭🇳', 'Peru': '🇵🇪',
        'Rwanda': '🇷🇼', 'Burundi': '🇧🇮', 'Indonesia': '🇮🇩', 'Yemen': '🇾🇪',
        'Panama': '🇵🇦', 'El Salvador': '🇸🇻', 'Nicaragua': '🇳🇮', 'Mexico': '🇲🇽'
    };
    return flags[origin] || '🌍';
}

/**
 * Render narrative with themed sections
 */
function renderNarrativeMagazine(narrative) {
    const featuredEl = document.getElementById('featured-story');
    const secondaryEl = document.getElementById('secondary-stories');

    // Hide secondary section - we'll use just featured for all content
    secondaryEl.innerHTML = '';

    if (!narrative) {
        featuredEl.innerHTML = '<p class="empty-text">No insights available.</p>';
        return;
    }

    // Parse into themed sections
    const themedSections = parseThemedSections(narrative);

    if (themedSections.length === 0) {
        // Fallback to old parsing if no themed sections found
        const sections = parseNarrativeSections(narrative);
        if (sections.length === 0) {
            featuredEl.innerHTML = '<p class="empty-text">No insights available.</p>';
            return;
        }
        featuredEl.innerHTML = sections.map(section => `
            <div class="news-section">
                <h3 class="news-headline">${getHeadlineIcon(section.headline)} ${escapeHtml(section.headline)}</h3>
                <p class="news-body">${section.body}</p>
            </div>
        `).join('');
        return;
    }

    // Render themed sections
    featuredEl.innerHTML = themedSections.map(section => `
        <div class="theme-section">
            <h2 class="theme-header">${section.themeHeader}</h2>
            <div class="theme-stories">
                ${section.stories.map(story => `
                    <div class="news-section">
                        <h3 class="news-headline">${escapeHtml(story.headline)}</h3>
                        <p class="news-body">${story.body}</p>
                    </div>
                `).join('')}
            </div>
        </div>
    `).join('');
}

/**
 * Parse narrative into themed sections (### headers)
 */
function parseThemedSections(narrative) {
    const sections = [];

    // Split by theme headers (### emoji Header)
    const themeParts = narrative.split(/###\s*/);

    for (let i = 1; i < themeParts.length; i++) {
        const part = themeParts[i].trim();
        if (!part) continue;

        // First line is the theme header
        const lines = part.split('\n');
        const themeHeader = lines[0].trim();
        const content = lines.slice(1).join('\n').trim();

        // Parse stories within this theme
        const stories = parseNarrativeSections(content);

        if (themeHeader && stories.length > 0) {
            sections.push({
                themeHeader,
                stories
            });
        }
    }

    return sections;
}

/**
 * Get appropriate icon for headline based on keywords
 */
function getHeadlineIcon(headline) {
    const lower = headline.toLowerCase();

    // Discovery/exploration
    if (lower.includes('discover') || lower.includes('explore') || lower.includes('find'))
        return '🔍';

    // Warning/shortage/alert
    if (lower.includes('shortage') || lower.includes('loom') || lower.includes('warning') || lower.includes('stock up') || lower.includes('deficit'))
        return '⚠️';

    // Origin spotlight
    if (lower.includes('shines') || lower.includes('spotlight') || lower.includes('gem') || lower.includes('underrated'))
        return '✨';

    // Quick tip/recommendation
    if (lower.includes('quick') || lower.includes('try') || lower.includes('pick') || lower.includes('tip'))
        return '💡';

    // Trending/rising
    if (lower.includes('trend') || lower.includes('rising') || lower.includes('gaining') || lower.includes('popular'))
        return '📈';

    // New arrivals
    if (lower.includes('new') || lower.includes('arrival') || lower.includes('fresh') || lower.includes('just in'))
        return '🆕';

    // Price/value
    if (lower.includes('price') || lower.includes('value') || lower.includes('deal') || lower.includes('affordable'))
        return '💰';

    // Award/quality
    if (lower.includes('award') || lower.includes('winner') || lower.includes('best') || lower.includes('exceptional'))
        return '🏆';

    // Default coffee icon
    return '☕';
}

/**
 * Parse narrative into headline + body sections
 * Format: **Headline.**\n\nBody text.\n\n**Next headline.**\n\nNext body.
 */
function parseNarrativeSections(narrative) {
    const sections = [];

    // Split by **headline** pattern - each match starts a new section
    const parts = narrative.split(/\*\*(.+?)\*\*/);

    // parts[0] = text before first headline (usually empty)
    // parts[1] = first headline
    // parts[2] = body after first headline
    // parts[3] = second headline
    // parts[4] = body after second headline
    // etc.

    for (let i = 1; i < parts.length; i += 2) {
        const headline = parts[i].trim();
        const body = parts[i + 1] ? enhanceText(parts[i + 1].trim()) : '';

        if (headline) {
            sections.push({ headline, body });
        }
    }

    return sections;
}

/**
 * Enhance text with number badges and clickable links
 */
function enhanceText(text) {
    let enhanced = escapeHtml(text);

    // Highlight numbers with context (e.g., "148 new", "91 products", "32 Gesha")
    // Pattern: number followed by relevant word
    enhanced = enhanced.replace(/(\d+)\s+(new|products?|coffees?|listings?|options?|roasters?|active)/gi,
        '<span class="stat-badge">$1</span> $2');

    // Also highlight standalone significant numbers with units
    enhanced = enhanced.replace(/(\d+)%/g, '<span class="stat-badge">$1%</span>');

    // Make origin countries clickable with flag emojis
    const originFlags = {
        'Ethiopia': '🇪🇹', 'Colombia': '🇨🇴', 'Brazil': '🇧🇷', 'Kenya': '🇰🇪',
        'Guatemala': '🇬🇹', 'Costa Rica': '🇨🇷', 'Honduras': '🇭🇳', 'Peru': '🇵🇪',
        'Rwanda': '🇷🇼', 'Burundi': '🇧🇮', 'Indonesia': '🇮🇩', 'Yemen': '🇾🇪',
        'Panama': '🇵🇦', 'El Salvador': '🇸🇻', 'Nicaragua': '🇳🇮', 'Mexico': '🇲🇽',
        'Uganda': '🇺🇬', 'Tanzania': '🇹🇿', 'Malawi': '🇲🇼', 'DRC': '🇨🇩',
        'Congo': '🇨🇩', 'Bolivia': '🇧🇴', 'Ecuador': '🇪🇨', 'Jamaica': '🇯🇲',
        'Hawaii': '🇺🇸', 'India': '🇮🇳', 'Vietnam': '🇻🇳', 'Papua New Guinea': '🇵🇬',
        'Myanmar': '🇲🇲', 'Thailand': '🇹🇭', 'China': '🇨🇳', 'Philippines': '🇵🇭'
    };

    Object.entries(originFlags).forEach(([origin, flag]) => {
        const regex = new RegExp(`\\b(${origin})\\b`, 'gi');
        enhanced = enhanced.replace(regex,
            `<a href="/filter-products.html?origin=${encodeURIComponent(origin)}" class="origin-link">${flag} $1</a>`);
    });

    // Make flavor terms clickable (exclude 'honey' - it's a process)
    const flavors = ['chocolate', 'caramel', 'fruity', 'citrus', 'berry', 'floral', 'nutty',
                     'vanilla', 'spicy', 'earthy', 'wine', 'tropical', 'stone fruit',
                     'umami', 'savory', 'sweet', 'bright', 'acidic', 'balanced'];

    flavors.forEach(flavor => {
        const regex = new RegExp(`\\b(${flavor})\\b`, 'gi');
        enhanced = enhanced.replace(regex,
            `<a href="/discover.html?flavor=${encodeURIComponent(flavor)}" class="flavor-link">$1</a>`);
    });

    // Make process terms clickable
    const processes = ['washed', 'natural', 'honey', 'anaerobic', 'carbonic', 'wet-hulled'];

    processes.forEach(process => {
        const regex = new RegExp(`\\b(${process})\\b`, 'gi');
        enhanced = enhanced.replace(regex,
            `<a href="/filter-products.html?process=${encodeURIComponent(process)}" class="process-link">$1</a>`);
    });

    // Make variety terms clickable
    const varieties = ['Gesha', 'Geisha', 'Bourbon', 'Typica', 'Caturra', 'Catuai', 'SL28', 'SL34', 'Pacamara'];

    varieties.forEach(variety => {
        const regex = new RegExp(`\\b(${variety})\\b`, 'gi');
        enhanced = enhanced.replace(regex,
            `<a href="/filter-products.html?variety=${encodeURIComponent(variety)}" class="variety-link">$1</a>`);
    });

    return enhanced;
}

/**
 * Render data insights from metrics with mini charts
 */
function renderInsights(metrics) {
    if (!metrics) return;

    // Color palette for charts
    const colors = {
        origins: ['#E07A5F', '#F2CC8F', '#81B29A', '#3D405B', '#F4F1DE'],
        flavors: ['#81B29A', '#A8DADC', '#457B9D', '#1D3557', '#F4F1DE'],
        processes: ['#F2CC8F', '#E07A5F', '#81B29A', '#3D405B', '#F4F1DE']
    };

    // Top Origins Chart
    if (metrics.originActivity && metrics.originActivity.topOrigins) {
        const origins = metrics.originActivity.topOrigins.slice(0, 5);
        renderMiniBarChart('origins-chart',
            origins.map(o => o.origin),
            origins.map(o => o.count),
            colors.origins
        );
    }

    // Top Flavors Chart
    if (metrics.topFlavors && metrics.topFlavors.topFlavors) {
        const flavors = metrics.topFlavors.topFlavors.slice(0, 5);
        renderMiniBarChart('flavors-chart',
            flavors.map(f => f.flavor),
            flavors.map(f => f.count),
            colors.flavors
        );
    }

    // Top Processes Chart
    if (metrics.processTrend && metrics.processTrend.topProcesses) {
        const processes = metrics.processTrend.topProcesses.slice(0, 5);
        renderMiniBarChart('processes-chart',
            processes.map(p => p.process),
            processes.map(p => p.count),
            colors.processes
        );
    }

    // Product Stats (keep as text)
    const statsEl = document.getElementById('product-stats');
    if (metrics.newProducts) {
        statsEl.innerHTML = `
            <div class="stat-row">
                <span class="stat-label">Total Products</span>
                <span class="stat-value">${metrics.newProducts.totalActive || 0}</span>
            </div>
            <div class="stat-row">
                <span class="stat-label">New This Week</span>
                <span class="stat-value highlight">+${metrics.newProducts.newThisWeek || 0}</span>
            </div>
        `;
    } else {
        statsEl.innerHTML = '<p class="empty-text">No data</p>';
    }
}

// Store chart instances to destroy before re-creating
const chartInstances = {};

/**
 * Render a mini horizontal bar chart
 */
function renderMiniBarChart(canvasId, labels, data, colors) {
    const ctx = document.getElementById(canvasId);
    if (!ctx) return;

    // Destroy existing chart if any
    if (chartInstances[canvasId]) {
        chartInstances[canvasId].destroy();
    }

    chartInstances[canvasId] = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [{
                data: data,
                backgroundColor: colors,
                borderRadius: 4,
                barThickness: 14
            }]
        },
        options: {
            indexAxis: 'y',
            responsive: true,
            maintainAspectRatio: true,
            aspectRatio: 2,
            animation: false,
            plugins: {
                legend: { display: false }
            },
            scales: {
                x: {
                    display: false,
                    grid: { display: false }
                },
                y: {
                    grid: { display: false },
                    ticks: {
                        font: { size: 11, family: 'Open Sans' },
                        color: '#666'
                    }
                }
            }
        }
    });
}

function renderSources(articles) {
    const list = document.getElementById('sources-list');

    if (!articles || articles.length === 0) {
        list.innerHTML = '-';
        return;
    }

    // Get unique sources with links
    const sourceMap = new Map();
    articles.forEach(article => {
        const sourceName = article.source.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
        if (!sourceMap.has(sourceName)) {
            sourceMap.set(sourceName, article.link);
        }
    });

    const sourceLinks = Array.from(sourceMap.entries()).map(([name, link]) =>
        `<a href="${escapeHtml(link)}" target="_blank" rel="noopener">${escapeHtml(name)}</a>`
    );

    list.innerHTML = sourceLinks.join(', ');
}

function formatDate(dateStr) {
    if (!dateStr) return '';
    const date = new Date(dateStr);
    return date.toLocaleDateString('en-GB', {
        day: 'numeric',
        month: 'short',
        year: 'numeric'
    });
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
