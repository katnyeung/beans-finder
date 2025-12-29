/**
 * Coffee Preference Guide - Multi-step discovery flow
 * Collects user preferences and redirects to discover.html with pre-filled filters
 * Uses hardcoded data for instant load (no API calls)
 */

// Guide state
let guideState = {
    currentSection: 0,
    selectedOrigins: [],     // Array of country names
    explorationPreference: null  // 'clean' | 'sweet' | 'fruity' | 'wild'
};

// Section definitions (2 sections - removed brands)
const sections = [
    {
        id: 'origins',
        title: 'What origins do you love?',
        hint: 'Pick your favorite coffee-growing countries. Skip if you\'re not sure yet.',
        multiSelect: true
    },
    {
        id: 'exploration',
        title: 'What style do you prefer?',
        hint: 'This affects how the coffee is processed and what flavors you\'ll taste.',
        multiSelect: false
    }
];

// Hardcoded popular origins (no API call needed)
const popularOrigins = [
    { country: 'Ethiopia', flag: '🇪🇹', desc: 'Fruity, floral, complex' },
    { country: 'Colombia', flag: '🇨🇴', desc: 'Balanced, nutty, sweet' },
    { country: 'Brazil', flag: '🇧🇷', desc: 'Chocolatey, nutty, low acid' },
    { country: 'Kenya', flag: '🇰🇪', desc: 'Bright, berry, wine-like' },
    { country: 'Guatemala', flag: '🇬🇹', desc: 'Chocolate, spice, balanced' },
    { country: 'Costa Rica', flag: '🇨🇷', desc: 'Clean, honey, citrus' },
    { country: 'Honduras', flag: '🇭🇳', desc: 'Sweet, fruity, caramel' },
    { country: 'Peru', flag: '🇵🇪', desc: 'Mild, nutty, chocolate' },
    { country: 'Rwanda', flag: '🇷🇼', desc: 'Bright, floral, tea-like' },
    { country: 'Burundi', flag: '🇧🇮', desc: 'Complex, fruity, sweet' },
    { country: 'El Salvador', flag: '🇸🇻', desc: 'Sweet, balanced, honey' },
    { country: 'Nicaragua', flag: '🇳🇮', desc: 'Fruity, balanced, citrus' },
    { country: 'Panama', flag: '🇵🇦', desc: 'Delicate, floral, tea-like' },
    { country: 'Indonesia', flag: '🇮🇩', desc: 'Earthy, bold, spicy' },
    { country: 'Mexico', flag: '🇲🇽', desc: 'Light, nutty, chocolate' },
    { country: 'Uganda', flag: '🇺🇬', desc: 'Fruity, winey, bold' }
];

// Exploration options (process only, no roast filter)
const explorationOptions = [
    {
        id: 'clean',
        icon: '🧼',
        label: 'Clean & Classic',
        subtext: 'Washed process - crisp, predictable, origin-focused flavors',
        process: 'Washed'
    },
    {
        id: 'sweet',
        icon: '🍯',
        label: 'Sweet & Balanced',
        subtext: 'Honey process - syrupy sweetness, fruit-forward',
        process: 'Honey'
    },
    {
        id: 'fruity',
        icon: '☀️',
        label: 'Fruity & Bold',
        subtext: 'Natural process - intense fruit, wine-like, full body',
        process: 'Natural'
    },
    {
        id: 'wild',
        icon: '🔬',
        label: 'Experimental & Wild',
        subtext: 'Anaerobic fermentation - funky, unique, surprising flavors',
        process: 'Anaerobic'
    }
];

// DOM elements
let guideContent, progressFill, progressText, backBtn, skipBtn, nextBtn;
let guideContainer, summaryContainer;

document.addEventListener('DOMContentLoaded', () => {
    guideContent = document.getElementById('guide-content');
    progressFill = document.getElementById('progress-fill');
    progressText = document.getElementById('progress-text');
    backBtn = document.getElementById('back-btn');
    skipBtn = document.getElementById('skip-btn');
    nextBtn = document.getElementById('next-btn');
    guideContainer = document.querySelector('.guide-container');
    summaryContainer = document.getElementById('guide-summary-container');

    // Setup navigation
    backBtn.addEventListener('click', goBack);
    skipBtn.addEventListener('click', skipSection);
    nextBtn.addEventListener('click', nextSection);

    // Setup summary buttons
    document.getElementById('summary-back-btn').addEventListener('click', () => {
        summaryContainer.style.display = 'none';
        guideContainer.style.display = 'block';
    });
    document.getElementById('discover-btn').addEventListener('click', redirectToDiscover);

    // Render first section immediately (no API calls needed)
    renderSection();
});

/**
 * Render current section
 */
function renderSection() {
    const section = sections[guideState.currentSection];

    // Update progress
    const progress = ((guideState.currentSection) / sections.length) * 100;
    progressFill.style.width = `${progress}%`;
    progressText.textContent = `Step ${guideState.currentSection + 1} of ${sections.length}`;

    // Show/hide back button
    backBtn.style.display = guideState.currentSection > 0 ? 'block' : 'none';

    // Update next button text for last section
    nextBtn.textContent = guideState.currentSection === sections.length - 1 ? 'See Results →' : 'Continue →';

    // Render section content
    if (section.id === 'origins') {
        renderOriginsSection();
    } else if (section.id === 'exploration') {
        renderExplorationSection();
    }
}

/**
 * Render origins multi-select section (uses hardcoded data)
 */
function renderOriginsSection() {
    const section = sections[0];

    guideContent.innerHTML = `
        <h2 class="guide-title">${section.title}</h2>
        <p class="guide-hint">${section.hint}</p>
        <div class="guide-grid" id="origins-grid">
            ${popularOrigins.map(origin => `
                <div class="guide-option ${guideState.selectedOrigins.includes(origin.country) ? 'selected' : ''}"
                     data-country="${escapeHtml(origin.country)}" data-type="origin">
                    <span class="option-icon">${origin.flag}</span>
                    <span class="option-name">${escapeHtml(origin.country)}</span>
                    <span class="option-count">${origin.desc}</span>
                </div>
            `).join('')}
        </div>
        <div class="selection-counter ${guideState.selectedOrigins.length === 0 ? 'empty' : ''}" id="origins-counter">
            ${guideState.selectedOrigins.length === 0 ? 'No origins selected (you can skip)' : `${guideState.selectedOrigins.length} origin${guideState.selectedOrigins.length > 1 ? 's' : ''} selected`}
        </div>
    `;

    // Add click handlers
    guideContent.querySelectorAll('.guide-option[data-type="origin"]').forEach(el => {
        el.addEventListener('click', () => toggleOriginSelection(el.dataset.country));
    });
}

/**
 * Render exploration single-select section
 */
function renderExplorationSection() {
    const section = sections[1];

    guideContent.innerHTML = `
        <h2 class="guide-title">${section.title}</h2>
        <p class="guide-hint">${section.hint}</p>
        <div class="guide-exploration-options">
            ${explorationOptions.map(opt => `
                <div class="guide-exploration-option ${guideState.explorationPreference === opt.id ? 'selected' : ''}"
                     data-id="${opt.id}">
                    <span class="exp-icon">${opt.icon}</span>
                    <div class="exp-content">
                        <span class="exp-label">${opt.label}</span>
                        <span class="exp-subtext">${opt.subtext}</span>
                    </div>
                </div>
            `).join('')}
        </div>
    `;

    // Add click handlers
    guideContent.querySelectorAll('.guide-exploration-option').forEach(el => {
        el.addEventListener('click', () => selectExplorationPreference(el.dataset.id));
    });
}

/**
 * Toggle origin selection
 */
function toggleOriginSelection(country) {
    const index = guideState.selectedOrigins.indexOf(country);
    if (index > -1) {
        guideState.selectedOrigins.splice(index, 1);
    } else {
        guideState.selectedOrigins.push(country);
    }
    renderOriginsSection();
}

/**
 * Select exploration preference (single select)
 */
function selectExplorationPreference(prefId) {
    guideState.explorationPreference = prefId;
    renderExplorationSection();
}

/**
 * Go back to previous section
 */
function goBack() {
    if (guideState.currentSection > 0) {
        guideState.currentSection--;
        renderSection();
    }
}

/**
 * Skip current section (clear selection and move forward)
 */
function skipSection() {
    nextSection();
}

/**
 * Move to next section or show summary
 */
function nextSection() {
    if (guideState.currentSection < sections.length - 1) {
        guideState.currentSection++;
        renderSection();
    } else {
        showSummary();
    }
}

/**
 * Show summary before redirect
 */
function showSummary() {
    guideContainer.style.display = 'none';
    summaryContainer.style.display = 'block';

    const summaryEl = document.getElementById('guide-summary');
    const pref = explorationOptions.find(o => o.id === guideState.explorationPreference);

    // Get flag for selected origins
    const getFlag = (country) => {
        const origin = popularOrigins.find(o => o.country === country);
        return origin ? origin.flag : '🌍';
    };

    summaryEl.innerHTML = `
        <h4>Your Selections</h4>

        <div class="summary-item">
            <span class="summary-label">Origins:</span>
            <div class="summary-badges">
                ${guideState.selectedOrigins.length > 0
                    ? guideState.selectedOrigins.map(o => `<span class="summary-badge">${getFlag(o)} ${escapeHtml(o)}</span>`).join('')
                    : '<span class="summary-value" style="color: #888;">None selected</span>'}
            </div>
        </div>

        <div class="summary-item">
            <span class="summary-label">Style:</span>
            <span class="summary-value">
                ${pref ? `${pref.icon} ${pref.label}` : '<span style="color: #888;">None selected</span>'}
            </span>
        </div>

        ${buildFilterPreview()}
    `;
}

/**
 * Build filter preview for summary
 */
function buildFilterPreview() {
    const filters = [];

    if (guideState.selectedOrigins.length > 0) {
        filters.push(`Origin: ${guideState.selectedOrigins[0]}`);
    }

    const pref = explorationOptions.find(o => o.id === guideState.explorationPreference);
    if (pref && pref.process) {
        filters.push(`Process: ${pref.process}`);
    }

    if (filters.length === 0) {
        return `<p style="margin-top: 1rem; color: #666; font-size: 0.9rem;">No filters will be applied - you'll see all coffees.</p>`;
    }

    return `
        <div style="margin-top: 1rem; padding-top: 1rem; border-top: 1px solid #e0e0e0;">
            <p style="color: #666; font-size: 0.9rem; margin-bottom: 0.5rem;">Filters to apply:</p>
            <div class="summary-badges">
                ${filters.map(f => `<span class="summary-badge">${f}</span>`).join('')}
            </div>
        </div>
    `;
}

/**
 * Build redirect URL and navigate to filter-products.html
 */
function redirectToDiscover() {
    const params = new URLSearchParams();

    // Add origin filter (comma-separated for multiple)
    if (guideState.selectedOrigins.length > 0) {
        params.set('origin', guideState.selectedOrigins.join(','));
    }

    // Add process from exploration preference (no roast filter)
    const pref = explorationOptions.find(o => o.id === guideState.explorationPreference);
    if (pref && pref.process) {
        params.set('process', pref.process);
    }

    // Build URL - use filter-products.html which has connected filters
    const queryString = params.toString();
    const url = queryString ? `/filter-products.html?${queryString}` : '/filter-products.html';

    window.location.href = url;
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
