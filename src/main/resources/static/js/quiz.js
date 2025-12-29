/**
 * Coffee Quiz - Conversational discovery flow
 * No LLM calls - uses existing APIs with client-side filtering
 */

// Quiz questions with friendly, conversational framing
const questions = [
    {
        id: 'brew',
        question: "How do you usually make your coffee at home?",
        hint: "This helps us suggest beans that will taste great with your setup.",
        options: [
            {
                id: 'filter',
                icon: '☕',
                label: 'Pour-over or filter',
                subtext: 'V60, Chemex, drip machine',
                roast: ['Light', 'Medium']
            },
            {
                id: 'espresso',
                icon: '⚡',
                label: 'Espresso machine',
                subtext: 'Home espresso setup',
                roast: ['Medium', 'Dark']
            },
            {
                id: 'immersion',
                icon: '🫖',
                label: 'French press or stovetop',
                subtext: 'Cafetière, Moka pot, Aeropress',
                roast: ['Medium']
            },
            {
                id: 'unsure',
                icon: '🤷',
                label: "I'm not sure yet",
                subtext: 'Still figuring it out',
                roast: ['Omni', 'Medium']
            }
        ]
    },
    {
        id: 'flavor',
        question: "What flavors do you enjoy in drinks generally?",
        hint: "Not just coffee - think about teas, juices, desserts you like.",
        options: [
            {
                id: 'fruity',
                icon: '🍋',
                label: 'Bright and fruity',
                subtext: 'Citrus, berries, fresh fruit',
                flavorCategory: 'fruity',
                process: ['Washed', 'Natural']
            },
            {
                id: 'rich',
                icon: '🍫',
                label: 'Rich and comforting',
                subtext: 'Chocolate, caramel, nuts',
                flavorCategory: 'nutty',
                process: ['Washed', 'Honey']
            },
            {
                id: 'floral',
                icon: '🌸',
                label: 'Floral and delicate',
                subtext: 'Jasmine tea, honey',
                flavorCategory: 'floral',
                process: ['Washed']
            },
            {
                id: 'surprise',
                icon: '🎲',
                label: 'Surprise me!',
                subtext: "I'm open to anything",
                flavorCategory: null,
                process: null
            }
        ]
    },
    {
        id: 'adventure',
        question: "How adventurous are you feeling?",
        hint: "Some coffees are classic crowd-pleasers. Others are wild experiments.",
        options: [
            {
                id: 'classic',
                icon: '🏠',
                label: 'Classic and reliable',
                subtext: 'I like knowing what to expect',
                process: ['Washed']
            },
            {
                id: 'adventurous',
                icon: '🌴',
                label: 'A little adventurous',
                subtext: 'Open to something different',
                process: ['Natural', 'Honey']
            },
            {
                id: 'wild',
                icon: '🚀',
                label: 'Show me the wild stuff',
                subtext: 'Funky, experimental, unusual',
                process: ['Anaerobic', 'Carbonic']
            }
        ]
    }
];

// User's answers
let answers = {};
let currentQuestion = 0;

// DOM elements
let quizContent, progressFill, progressText, backBtn, quizNav;
let quizContainer, resultsContainer, loadingContainer;

document.addEventListener('DOMContentLoaded', () => {
    quizContent = document.getElementById('quiz-content');
    progressFill = document.getElementById('progress-fill');
    progressText = document.getElementById('progress-text');
    backBtn = document.getElementById('back-btn');
    quizNav = document.getElementById('quiz-nav');
    quizContainer = document.querySelector('.quiz-container');
    resultsContainer = document.getElementById('quiz-results');
    loadingContainer = document.getElementById('quiz-loading');

    // Setup back button
    backBtn.addEventListener('click', goBack);

    // Setup retry button
    document.getElementById('retry-btn').addEventListener('click', resetQuiz);

    // Render first question
    renderQuestion();
});

/**
 * Render current question
 */
function renderQuestion() {
    const q = questions[currentQuestion];

    // Update progress
    const progress = ((currentQuestion) / questions.length) * 100;
    progressFill.style.width = `${progress}%`;
    progressText.textContent = `Question ${currentQuestion + 1} of ${questions.length}`;

    // Show/hide back button
    backBtn.style.display = currentQuestion > 0 ? 'block' : 'none';

    // Render question
    quizContent.innerHTML = `
        <h2 class="quiz-question">${q.question}</h2>
        <p class="quiz-hint">${q.hint}</p>
        <div class="quiz-options">
            ${q.options.map(opt => `
                <button class="quiz-option ${answers[q.id] === opt.id ? 'selected' : ''}"
                        data-option-id="${opt.id}">
                    <span class="option-icon">${opt.icon}</span>
                    <div class="option-content">
                        <span class="option-label">${opt.label}</span>
                        <span class="option-subtext">${opt.subtext}</span>
                    </div>
                </button>
            `).join('')}
        </div>
    `;

    // Add click handlers
    quizContent.querySelectorAll('.quiz-option').forEach(btn => {
        btn.addEventListener('click', () => selectOption(q.id, btn.dataset.optionId));
    });
}

/**
 * Handle option selection
 */
function selectOption(questionId, optionId) {
    answers[questionId] = optionId;

    // Visual feedback
    quizContent.querySelectorAll('.quiz-option').forEach(btn => {
        btn.classList.toggle('selected', btn.dataset.optionId === optionId);
    });

    // Auto-advance after short delay
    setTimeout(() => {
        if (currentQuestion < questions.length - 1) {
            currentQuestion++;
            renderQuestion();
        } else {
            showResults();
        }
    }, 300);
}

/**
 * Go back to previous question
 */
function goBack() {
    if (currentQuestion > 0) {
        currentQuestion--;
        renderQuestion();
    }
}

/**
 * Reset quiz
 */
function resetQuiz() {
    answers = {};
    currentQuestion = 0;
    quizContainer.style.display = 'block';
    resultsContainer.style.display = 'none';
    loadingContainer.style.display = 'none';
    renderQuestion();
}

/**
 * Show results based on answers
 */
async function showResults() {
    // Hide quiz, show loading
    quizContainer.style.display = 'none';
    loadingContainer.style.display = 'flex';

    try {
        const products = await fetchMatchingProducts();
        renderResults(products);
    } catch (error) {
        console.error('Error fetching products:', error);
        renderResults([]);
    }

    // Hide loading, show results
    loadingContainer.style.display = 'none';
    resultsContainer.style.display = 'block';
}

/**
 * Fetch products matching user's preferences
 */
async function fetchMatchingProducts() {
    // Build filter criteria from answers
    const criteria = buildFilterCriteria();

    // Try to fetch by process first (most predictive per James Hoffmann)
    let products = [];

    if (criteria.process && criteria.process.length > 0) {
        // Try each process type
        for (const process of criteria.process) {
            try {
                const response = await fetch(`/api/products/process/${encodeURIComponent(process)}`);
                if (response.ok) {
                    const data = await response.json();
                    products = products.concat(data);
                }
            } catch (e) {
                console.warn(`Failed to fetch process ${process}:`, e);
            }
        }
    }

    // If no products from process, try flavor category
    if (products.length === 0 && criteria.flavorCategory) {
        try {
            const response = await fetch(`/api/discover/flavor/${encodeURIComponent(criteria.flavorCategory)}/similar`);
            if (response.ok) {
                products = await response.json();
            }
        } catch (e) {
            console.warn('Failed to fetch by flavor:', e);
        }
    }

    // Fallback: get all products from brands
    if (products.length === 0) {
        try {
            const response = await fetch('/api/brands/approved');
            if (response.ok) {
                const brands = await response.json();
                // Get products from first few brands
                for (const brand of brands.slice(0, 5)) {
                    const prodResponse = await fetch(`/api/products/brand/${brand.id}`);
                    if (prodResponse.ok) {
                        const brandProducts = await prodResponse.json();
                        products = products.concat(brandProducts);
                    }
                }
            }
        } catch (e) {
            console.warn('Fallback fetch failed:', e);
        }
    }

    // Remove duplicates by ID
    const seen = new Set();
    products = products.filter(p => {
        if (seen.has(p.id)) return false;
        seen.add(p.id);
        return true;
    });

    // Filter by roast level if specified
    if (criteria.roast && criteria.roast.length > 0) {
        const roastFiltered = products.filter(p =>
            !p.roastLevel || criteria.roast.some(r =>
                p.roastLevel && p.roastLevel.toLowerCase().includes(r.toLowerCase())
            )
        );
        // Only use filtered if we have enough results
        if (roastFiltered.length >= 3) {
            products = roastFiltered;
        }
    }

    // Shuffle and limit to 9
    products = shuffleArray(products).slice(0, 9);

    return products;
}

/**
 * Build filter criteria from user answers
 */
function buildFilterCriteria() {
    const criteria = {
        roast: [],
        process: [],
        flavorCategory: null
    };

    // Q1: Brew method -> roast preference
    const brewAnswer = questions[0].options.find(o => o.id === answers.brew);
    if (brewAnswer && brewAnswer.roast) {
        criteria.roast = brewAnswer.roast;
    }

    // Q2: Flavor preference
    const flavorAnswer = questions[1].options.find(o => o.id === answers.flavor);
    if (flavorAnswer) {
        if (flavorAnswer.flavorCategory) {
            criteria.flavorCategory = flavorAnswer.flavorCategory;
        }
        if (flavorAnswer.process) {
            criteria.process = flavorAnswer.process;
        }
    }

    // Q3: Adventure level -> process (override)
    const adventureAnswer = questions[2].options.find(o => o.id === answers.adventure);
    if (adventureAnswer && adventureAnswer.process) {
        // Adventure level is most important for process
        criteria.process = adventureAnswer.process;
    }

    return criteria;
}

/**
 * Render results with educational card layout
 */
function renderResults(products) {
    const grid = document.getElementById('results-grid');
    const criteria = buildFilterCriteria();

    if (products.length === 0) {
        grid.innerHTML = `
            <div class="no-results">
                <p>We couldn't find exact matches for your preferences.</p>
                <p>Try <a href="/discover.html">exploring all coffees</a> or <a href="/chat.html">chat with our AI</a> for personalized help.</p>
            </div>
        `;
        return;
    }

    // Limit to 6 cards
    const displayProducts = products.slice(0, 6);

    grid.innerHTML = displayProducts.map(p => {
        const chatUrl = buildChatUrl(p);
        const tastingNotes = renderTastingNotes(p.tastingNotesJson);
        const originLine = renderOriginLine(p);
        const detailsLine = renderDetailsLine(p);
        const characterDesc = renderCharacterDescription(p.characterAxesJson);
        const tip = generateEducationalTip(p, criteria);

        return `
            <div class="result-card">
                <a href="/product-detail.html?id=${p.id}" class="result-card-link">
                    <div class="result-header">
                        <h3 class="result-name">${escapeHtml(p.productName)}</h3>
                        <span class="result-brand">${escapeHtml(p.brand?.name || p.brandName || '')}</span>
                    </div>

                    ${tastingNotes ? `
                        <div class="result-tasting-notes">
                            <span class="section-label">Tasting Notes:</span>
                            <div class="tasting-badges">${tastingNotes}</div>
                        </div>
                    ` : ''}

                    ${originLine ? `<div class="result-origin-line">${originLine}</div>` : ''}
                    ${detailsLine ? `<div class="result-details-line">${detailsLine}</div>` : ''}

                    ${characterDesc ? `
                        <div class="result-character">
                            ${characterDesc}
                        </div>
                    ` : ''}

                    ${tip ? `<div class="result-tip">💡 ${tip}</div>` : ''}

                    <div class="result-footer">
                        ${p.price ? `<span class="result-price">£${p.price.toFixed(2)}</span>` : ''}
                        <a href="${chatUrl}" class="result-ask-ai" onclick="event.stopPropagation();">💬 More questions?</a>
                    </div>
                </a>
            </div>
        `;
    }).join('');
}

/**
 * Render tasting notes as badges
 */
function renderTastingNotes(tastingNotesJson) {
    if (!tastingNotesJson) return '';

    try {
        const notes = typeof tastingNotesJson === 'string'
            ? JSON.parse(tastingNotesJson)
            : tastingNotesJson;

        if (!Array.isArray(notes) || notes.length === 0) return '';

        // Limit to 5 notes, add emoji prefixes
        return notes.slice(0, 5).map(note => {
            const emoji = getTastingNoteEmoji(note);
            return `<span class="tasting-badge">${emoji} ${escapeHtml(note)}</span>`;
        }).join('');
    } catch (e) {
        return '';
    }
}

/**
 * Get emoji for common tasting notes
 */
function getTastingNoteEmoji(note) {
    const lower = note.toLowerCase();
    // Fruits
    if (lower.includes('blueberry') || lower.includes('berry')) return '🫐';
    if (lower.includes('strawberry')) return '🍓';
    if (lower.includes('citrus') || lower.includes('lemon') || lower.includes('orange')) return '🍋';
    if (lower.includes('apple')) return '🍎';
    if (lower.includes('peach') || lower.includes('apricot')) return '🍑';
    if (lower.includes('tropical') || lower.includes('mango')) return '🥭';
    if (lower.includes('grape')) return '🍇';
    if (lower.includes('cherry')) return '🍒';
    // Floral
    if (lower.includes('floral') || lower.includes('jasmine') || lower.includes('rose')) return '🌸';
    if (lower.includes('honey')) return '🍯';
    // Sweet
    if (lower.includes('chocolate') || lower.includes('cocoa')) return '🍫';
    if (lower.includes('caramel') || lower.includes('toffee')) return '🍬';
    if (lower.includes('vanilla')) return '🍦';
    if (lower.includes('brown sugar') || lower.includes('molasses')) return '🧁';
    // Nutty
    if (lower.includes('nut') || lower.includes('almond') || lower.includes('hazelnut')) return '🥜';
    // Spicy
    if (lower.includes('spice') || lower.includes('cinnamon') || lower.includes('clove')) return '🌶️';
    // Default
    return '•';
}

/**
 * Render origin line
 */
function renderOriginLine(product) {
    const parts = [];
    if (product.origin) parts.push(product.origin);
    if (product.region && product.region !== product.origin) parts.push(product.region);

    if (parts.length === 0) return '';
    return `📍 ${escapeHtml(parts.join(', '))}`;
}

/**
 * Render details line (process, variety, altitude)
 */
function renderDetailsLine(product) {
    const parts = [];

    if (product.process) {
        parts.push(getProcessLabel(product.process));
    }
    if (product.variety) {
        parts.push(escapeHtml(product.variety));
    }
    if (product.altitude) {
        parts.push(escapeHtml(product.altitude));
    }

    return parts.join('  •  ');
}

/**
 * Render character description from characterAxesJson
 */
function renderCharacterDescription(characterAxesJson) {
    if (!characterAxesJson) return '';

    try {
        const axes = typeof characterAxesJson === 'string'
            ? JSON.parse(characterAxesJson)
            : characterAxesJson;

        if (!Array.isArray(axes) || axes.length < 4) return '';

        const descriptions = [];

        // Acidity (index 0)
        const acidity = axes[0];
        if (acidity > 0.3) {
            descriptions.push('<span class="char-line">🍋 Bright acidity - lively and citrusy</span>');
        } else if (acidity < -0.3) {
            descriptions.push('<span class="char-line">🧈 Low acidity - smooth and mellow</span>');
        } else if (acidity !== 0) {
            descriptions.push('<span class="char-line">⚖️ Balanced acidity</span>');
        }

        // Body (index 1)
        const body = axes[1];
        if (body > 0.3) {
            descriptions.push('<span class="char-line">🍫 Full body - rich and creamy</span>');
        } else if (body < -0.3) {
            descriptions.push('<span class="char-line">🪶 Light body - tea-like and delicate</span>');
        } else if (body !== 0) {
            descriptions.push('<span class="char-line">☕ Medium body</span>');
        }

        // Roast (index 2)
        const roast = axes[2];
        if (roast > 0.3) {
            descriptions.push('<span class="char-line">🔥 Dark roast - bold and intense</span>');
        } else if (roast < -0.3) {
            descriptions.push('<span class="char-line">☀️ Light roast - origin flavors shine</span>');
        }

        // Complexity (index 3) - only show if notable
        const complexity = axes[3];
        if (complexity > 0.3) {
            descriptions.push('<span class="char-line">🎭 Complex - layers of interesting flavors</span>');
        } else if (complexity < -0.3) {
            descriptions.push('<span class="char-line">✨ Clean - pure and straightforward</span>');
        }

        return descriptions.join('');
    } catch (e) {
        return '';
    }
}

/**
 * Generate educational tip based on quiz answers
 */
function generateEducationalTip(product, criteria) {
    // Match tip to user's brew method answer
    if (answers.brew === 'filter' && product.roastLevel) {
        const roast = product.roastLevel.toLowerCase();
        if (roast.includes('light') || roast.includes('medium')) {
            return 'Perfect for pour-over brewing';
        }
    }

    if (answers.brew === 'espresso') {
        return 'Great as espresso or milk-based drinks';
    }

    if (answers.brew === 'immersion') {
        return 'Works well in French press';
    }

    // Match to flavor preference
    if (answers.flavor === 'fruity' && product.origin) {
        const origin = product.origin.toLowerCase();
        if (origin.includes('ethiopia') || origin.includes('kenya')) {
            return 'African coffees are known for fruity brightness';
        }
    }

    if (answers.flavor === 'rich') {
        return 'Rich and comforting - great any time of day';
    }

    // Default based on process
    if (product.process) {
        const process = product.process.toLowerCase();
        if (process.includes('natural')) {
            return 'Natural process adds fruity sweetness';
        }
        if (process.includes('washed')) {
            return 'Washed process for clean, clear flavors';
        }
    }

    return '';
}

/**
 * Generate a "why we picked this" reason
 */
function generateReason(product, criteria) {
    const reasons = [];

    // Process-based reason
    if (product.process) {
        const processLower = product.process.toLowerCase();
        if (processLower.includes('washed')) {
            reasons.push('Clean and crisp character');
        } else if (processLower.includes('natural')) {
            reasons.push('Sun-dried, fruity sweetness');
        } else if (processLower.includes('honey')) {
            reasons.push('Sweet and balanced');
        } else if (processLower.includes('anaerobic') || processLower.includes('carbonic')) {
            reasons.push('Experimental, wild flavors');
        }
    }

    // Roast-based reason
    if (product.roastLevel) {
        const roastLower = product.roastLevel.toLowerCase();
        if (roastLower.includes('light')) {
            reasons.push('Bright and lively');
        } else if (roastLower.includes('dark')) {
            reasons.push('Bold and rich');
        } else if (roastLower.includes('omni')) {
            reasons.push('Versatile for any brew method');
        }
    }

    // Origin-based reason
    if (product.origin) {
        const originLower = product.origin.toLowerCase();
        if (originLower.includes('ethiopia')) {
            reasons.push('Classic Ethiopian complexity');
        } else if (originLower.includes('colombia')) {
            reasons.push('Smooth Colombian balance');
        } else if (originLower.includes('kenya')) {
            reasons.push('Vibrant Kenyan brightness');
        } else if (originLower.includes('brazil')) {
            reasons.push('Nutty Brazilian sweetness');
        }
    }

    // Fallback
    if (reasons.length === 0) {
        reasons.push('Matches your preferences');
    }

    return reasons.slice(0, 2).join(' • ');
}

/**
 * Build chat URL with product context (same as product detail page)
 */
function buildChatUrl(product) {
    const params = new URLSearchParams();
    params.set('chatbotProductId', product.id);
    params.set('chatbotProductName', product.productName);

    if (product.brand?.id || product.brandId) {
        params.set('brandId', product.brand?.id || product.brandId);
    }
    if (product.brand?.name || product.brandName) {
        params.set('brandName', product.brand?.name || product.brandName);
    }
    if (product.origin) params.set('origin', product.origin);
    if (product.region) params.set('region', product.region);
    if (product.roastLevel) params.set('roastLevel', product.roastLevel);
    if (product.process) params.set('process', product.process);
    if (product.price) params.set('price', product.price);

    return `/chat.html?${params.toString()}`;
}

/**
 * Get friendly process label
 */
function getProcessLabel(process) {
    if (!process) return '';
    const lower = process.toLowerCase();
    if (lower.includes('washed')) return '🧼 Clean & crisp';
    if (lower.includes('natural')) return '☀️ Sun-dried';
    if (lower.includes('honey')) return '🍯 Honey process';
    if (lower.includes('anaerobic')) return '🔬 Experimental';
    return process;
}

/**
 * Shuffle array (Fisher-Yates)
 */
function shuffleArray(array) {
    const arr = [...array];
    for (let i = arr.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [arr[i], arr[j]] = [arr[j], arr[i]];
    }
    return arr;
}

/**
 * Escape HTML
 */
function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
