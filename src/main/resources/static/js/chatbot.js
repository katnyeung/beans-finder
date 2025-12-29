/**
 * Chatbot JavaScript for RAG-powered coffee recommendations
 * Integrated into products.html and brands.html
 *
 * IMPORTANT: All conversation state is client-side (localStorage) - no database persistence
 */

const CHATBOT_API_BASE = '/api/chatbot';

// Chatbot state (all client-side)
let conversationHistory = []; // Array of {role: 'user'|'assistant', content: '...', products: [...]}
let shownProductIds = []; // Track products already shown to avoid duplicates
let referenceProductId = null; // Current reference product for comparisons
let isWaitingForResponse = false;

// LocalStorage keys
const STORAGE_KEY_CONVERSATION = 'chatbot_conversation_history';
const STORAGE_KEY_SHOWN_PRODUCTS = 'chatbot_shown_products';
const STORAGE_KEY_REFERENCE_PRODUCT = 'chatbot_reference_product';
const STORAGE_KEY_ANON_CHAT_COUNT = 'chatbot_anonymous_count';
const STORAGE_KEY_ANON_CHAT_DATE = 'chatbot_anonymous_date';

// Freemium limit for anonymous users (resets daily)
const ANONYMOUS_CHAT_LIMIT = 5;

// Currency symbols mapping
const CURRENCY_SYMBOLS = {
    'GBP': '£',
    'USD': '$',
    'EUR': '€',
    'JPY': '¥',
    'CNY': '¥',
    'AUD': 'A$',
    'CAD': 'C$',
    'CHF': 'CHF ',
    'KRW': '₩',
    'SGD': 'S$',
    'HKD': 'HK$',
    'NZD': 'NZ$',
    'SEK': 'kr',
    'NOK': 'kr',
    'DKK': 'kr'
};

/**
 * Format price with correct currency symbol
 */
function formatPrice(price, currency) {
    const symbol = CURRENCY_SYMBOLS[currency] || CURRENCY_SYMBOLS['GBP'];
    return `${symbol}${price.toFixed(2)}`;
}

/**
 * Initialize chatbot
 */
async function initChatbot() {
    console.log('Initializing chatbot...');

    // Load state from localStorage
    loadStateFromStorage();

    // Setup event listeners
    const chatInput = document.getElementById('chat-input');
    const chatSendBtn = document.getElementById('chat-send-btn');
    const chatClearBtn = document.getElementById('chat-clear-btn');

    if (chatSendBtn) {
        chatSendBtn.addEventListener('click', sendMessage);
    }

    if (chatInput) {
        chatInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendMessage();
            }
        });
        // Auto-focus on input
        chatInput.focus();
    }

    if (chatClearBtn) {
        chatClearBtn.addEventListener('click', clearConversation);
    }

    // Check for URL params (from product detail page "Ask Chatbot" button)
    checkUrlParamsForChatbot();

    // Check for personalized actions (after auth loads - small delay)
    setTimeout(() => checkPersonalizedActions(), 500);

    console.log('Chatbot initialized. History length:', conversationHistory.length);
}

/**
 * Check URL params for chatbot prefill (from product detail page)
 */
function checkUrlParamsForChatbot() {
    const urlParams = new URLSearchParams(window.location.search);
    const chatbotProductId = urlParams.get('chatbotProductId');
    const chatbotProductName = urlParams.get('chatbotProductName');

    if (chatbotProductId && chatbotProductName) {
        console.log('Chatbot URL params found:', chatbotProductId, chatbotProductName);

        // Clear existing conversation (reuse the clear button logic)
        clearConversation();

        // Set reference product for this product
        referenceProductId = parseInt(chatbotProductId);
        saveStateToStorage();

        // Build product info card from URL params
        // Note: URLSearchParams.get() already decodes, so no need for decodeURIComponent
        const productName = chatbotProductName;
        const brandName = urlParams.get('brandName') || '';
        const origin = urlParams.get('origin') || '';
        const region = urlParams.get('region') || '';
        const roastLevel = urlParams.get('roastLevel') || '';
        const process = urlParams.get('process') || '';
        const price = urlParams.get('price') || '';
        const currency = urlParams.get('currency') || 'GBP';
        const tastingNotes = urlParams.get('tastingNotes') || '';

        // Display product info as a styled card
        displayProductInfoCard({
            id: chatbotProductId,
            name: productName,
            brand: brandName,
            origin: origin,
            region: region,
            roastLevel: roastLevel,
            process: process,
            price: price,
            currency: currency,
            tastingNotes: tastingNotes ? tastingNotes.split(',') : []
        });

        // Pre-fill chat input with a suggestion
        const chatInput = document.getElementById('chat-input');
        if (chatInput) {
            chatInput.value = '';
            chatInput.placeholder = `Ask about ${productName}, e.g., "Find similar coffees" or "Show me more from ${origin || 'this origin'}"`;
            chatInput.focus();
        }

        // Clean up URL
        const newUrl = window.location.pathname;
        window.history.replaceState({}, '', newUrl);
    }
}

/**
 * Display product info card (no LLM call, just show prefilled data)
 */
function displayProductInfoCard(product) {
    const chatMessages = document.getElementById('chat-messages');
    if (!chatMessages) return;

    const messageDiv = document.createElement('div');
    messageDiv.className = 'chat-message bot-message product-info-card';

    // Build details list
    let detailsHtml = '<ul class="product-details-list">';
    if (product.brand) detailsHtml += `<li><strong>Brand:</strong> ${product.brand}</li>`;
    if (product.origin) {
        const location = product.region ? `${product.region}, ${product.origin}` : product.origin;
        detailsHtml += `<li><strong>Origin:</strong> ${location}</li>`;
    }
    if (product.roastLevel) detailsHtml += `<li><strong>Roast:</strong> ${product.roastLevel}</li>`;
    if (product.process) detailsHtml += `<li><strong>Process:</strong> ${product.process}</li>`;
    if (product.price) detailsHtml += `<li><strong>Price:</strong> ${product.currency} ${product.price}</li>`;
    if (product.tastingNotes && product.tastingNotes.length > 0) {
        detailsHtml += `<li><strong>Tasting Notes:</strong> ${product.tastingNotes.join(', ')}</li>`;
    }
    detailsHtml += '</ul>';

    messageDiv.innerHTML = `
        <div class="message-text">
            <div class="product-info-header">
                <strong>${product.name}</strong>
                <a href="/product-detail.html?id=${product.id}" target="_blank" class="view-product-link">View Product</a>
            </div>
            ${detailsHtml}
            <p class="product-info-prompt">What would you like to know?</p>
        </div>
    `;

    chatMessages.appendChild(messageDiv);

    // Build quick action buttons based on product attributes (simplified - max 5 options)
    const quickActions = [];

    quickActions.push({ label: 'Similar Flavors', icon: '🎯', intent: 'SIMILAR_FLAVORS' });
    quickActions.push({ label: 'Similar Direction', icon: '📊', intent: 'similar_profile' });

    if (product.origin) {
        quickActions.push({ label: `More from ${product.origin}`, icon: '🌍', intent: 'SAME_ORIGIN' });
    }

    // Simple taste spectrum: Fruity (bright) vs Bold (rich)
    quickActions.push({ label: 'More Fruity', icon: '🍓', intent: 'more_fruity' });
    quickActions.push({ label: 'More Bold', icon: '☕', intent: 'more_roasted' });

    // Create quick actions container (reuse existing styles)
    const actionsContainer = document.createElement('div');
    actionsContainer.className = 'chat-quick-actions';

    const actionsRow = document.createElement('div');
    actionsRow.className = 'quick-actions-row';

    quickActions.forEach(action => {
        const actionButton = createQuickActionButton(action);
        actionsRow.appendChild(actionButton);
    });

    actionsContainer.appendChild(actionsRow);
    chatMessages.appendChild(actionsContainer);

    chatMessages.scrollTop = chatMessages.scrollHeight;
}

/**
 * Load state from localStorage
 */
async function loadStateFromStorage() {
    try {
        // Load conversation history
        const savedConversation = localStorage.getItem(STORAGE_KEY_CONVERSATION);
        if (savedConversation) {
            conversationHistory = JSON.parse(savedConversation);
            console.log('Loaded conversation history from localStorage:', conversationHistory.length, 'messages');

            // Display conversation in UI
            displayConversationFromHistory();
        }

        // Load shown products
        const savedShownProducts = localStorage.getItem(STORAGE_KEY_SHOWN_PRODUCTS);
        if (savedShownProducts) {
            shownProductIds = JSON.parse(savedShownProducts);
            console.log('Loaded shown product IDs from localStorage:', shownProductIds.length, 'products');
        }

        // Load reference product
        const savedReferenceProduct = localStorage.getItem(STORAGE_KEY_REFERENCE_PRODUCT);
        if (savedReferenceProduct) {
            referenceProductId = parseInt(savedReferenceProduct, 10);
            console.log('Loaded reference product ID from localStorage:', referenceProductId);
        }
    } catch (err) {
        console.error('Failed to load state from localStorage:', err);
    }
}

/**
 * Display conversation from history (on page load)
 */
function displayConversationFromHistory() {
    const chatMessages = document.getElementById('chat-messages');
    chatMessages.innerHTML = ''; // Clear welcome message

    if (conversationHistory.length === 0) {
        return;
    }

    conversationHistory.forEach(msg => {
        if (msg.role === 'user') {
            displayMessage(msg.content, 'user');
        } else if (msg.role === 'assistant') {
            displayMessage(msg.content, 'bot');

            // If this message has products, display them
            if (msg.products && msg.products.length > 0) {
                displayProductCards(msg.products);
            }
        }
    });
}

/**
 * Save state to localStorage
 */
function saveStateToStorage() {
    try {
        // Save conversation history
        localStorage.setItem(STORAGE_KEY_CONVERSATION, JSON.stringify(conversationHistory));

        // Save shown products
        localStorage.setItem(STORAGE_KEY_SHOWN_PRODUCTS, JSON.stringify(shownProductIds));

        // Save reference product
        if (referenceProductId) {
            localStorage.setItem(STORAGE_KEY_REFERENCE_PRODUCT, referenceProductId.toString());
        } else {
            localStorage.removeItem(STORAGE_KEY_REFERENCE_PRODUCT);
        }

        console.log('State saved to localStorage');
    } catch (err) {
        console.error('Failed to save state to localStorage:', err);
    }
}

/**
 * Get today's date as YYYY-MM-DD string
 */
function getTodayDateString() {
    return new Date().toISOString().split('T')[0];
}

/**
 * Check if anonymous user has reached chat limit
 * Returns true if user can chat, false if limit reached
 * Resets count daily
 */
function checkAnonymousLimit() {
    // If user is signed in, no limit
    if (typeof currentUser !== 'undefined' && currentUser) {
        return true;
    }

    // Check if we need to reset (new day)
    const storedDate = localStorage.getItem(STORAGE_KEY_ANON_CHAT_DATE);
    const today = getTodayDateString();

    if (storedDate !== today) {
        // New day - reset count
        localStorage.setItem(STORAGE_KEY_ANON_CHAT_COUNT, '0');
        localStorage.setItem(STORAGE_KEY_ANON_CHAT_DATE, today);
        return true;
    }

    const anonCount = parseInt(localStorage.getItem(STORAGE_KEY_ANON_CHAT_COUNT) || '0');
    return anonCount < ANONYMOUS_CHAT_LIMIT;
}

/**
 * Increment anonymous chat count (call after successful response)
 */
function incrementAnonymousChatCount() {
    if (typeof currentUser !== 'undefined' && currentUser) {
        return; // Don't count for signed-in users
    }

    // Ensure date is set
    const today = getTodayDateString();
    const storedDate = localStorage.getItem(STORAGE_KEY_ANON_CHAT_DATE);
    if (storedDate !== today) {
        localStorage.setItem(STORAGE_KEY_ANON_CHAT_COUNT, '0');
        localStorage.setItem(STORAGE_KEY_ANON_CHAT_DATE, today);
    }

    const anonCount = parseInt(localStorage.getItem(STORAGE_KEY_ANON_CHAT_COUNT) || '0');
    localStorage.setItem(STORAGE_KEY_ANON_CHAT_COUNT, (anonCount + 1).toString());
    console.log('Anonymous chat count:', anonCount + 1, '/', ANONYMOUS_CHAT_LIMIT, '(resets daily)');
}

/**
 * Display sign-in prompt when limit reached
 */
function displaySignInPrompt() {
    const chatMessages = document.getElementById('chat-messages');
    const messageDiv = document.createElement('div');
    messageDiv.className = 'chat-message bot-message signin-prompt';

    messageDiv.innerHTML = `
        <div class="message-text">
            <strong>You've used your 5 free chats for today!</strong>
            <p>Sign in with Google for unlimited chats and personalized recommendations based on your taste preferences.</p>
            <button onclick="signIn()" class="btn-signin-chat">
                <svg width="18" height="18" viewBox="0 0 24 24"><path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/><path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/><path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/><path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/></svg>
                Sign in with Google
            </button>
        </div>
    `;

    chatMessages.appendChild(messageDiv);
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

/**
 * Send message to chatbot
 */
async function sendMessage() {
    const chatInput = document.getElementById('chat-input');
    const query = chatInput.value.trim();

    if (!query || isWaitingForResponse) {
        return;
    }

    // Check freemium limit for anonymous users
    if (!checkAnonymousLimit()) {
        displaySignInPrompt();
        return;
    }

    console.log('Sending message:', query);

    // Add user message to history
    conversationHistory.push({
        role: 'user',
        content: query
    });

    // Display user message
    displayMessage(query, 'user');

    // Log chat question for analytics
    logChatQuestion(query);

    // Clear input
    chatInput.value = '';
    isWaitingForResponse = true;
    updateSendButton(true);

    // Show loading indicator
    showLoadingIndicator();

    try {
        // Build request with full client-side state
        const request = {
            query: query,
            messages: conversationHistory,
            shownProductIds: shownProductIds,
            referenceProductId: referenceProductId
        };

        console.log('Request:', {
            query: query,
            historyLength: conversationHistory.length,
            shownProductsCount: shownProductIds.length,
            referenceProductId: referenceProductId
        });

        // Call API
        const response = await fetch(`${CHATBOT_API_BASE}/query`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(request)
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();
        console.log('Response:', data);

        // Add assistant message to history with products
        conversationHistory.push({
            role: 'assistant',
            content: data.explanation,
            products: data.products || []
        });

        // Add shown product IDs
        if (data.products && data.products.length > 0) {
            data.products.forEach(product => {
                if (!shownProductIds.includes(product.id)) {
                    shownProductIds.push(product.id);
                }
            });

            // Auto-set reference product from first result (for quick actions to work)
            // User can still override by clicking "Find Similar" on specific products
            if (!referenceProductId) {
                referenceProductId = data.products[0].id;
                console.log('Auto-set reference product to:', referenceProductId, data.products[0].name);
            }
        }

        // Save state
        saveStateToStorage();

        // Log chat answer for analytics (track which brands appeared in recommendations)
        if (data.products && data.products.length > 0) {
            logChatAnswer(data.products);
        }

        // Increment anonymous chat count on successful response
        incrementAnonymousChatCount();

        // Remove loading indicator and display bot response
        removeLoadingIndicator();
        displayBotResponse(data);

    } catch (err) {
        console.error('Error:', err);
        removeLoadingIndicator();
        const errorMsg = 'Sorry, I encountered an error. Please try again.';

        // Add error to history
        conversationHistory.push({
            role: 'assistant',
            content: errorMsg
        });
        saveStateToStorage();

        displayMessage(errorMsg, 'bot');
    } finally {
        isWaitingForResponse = false;
        updateSendButton(false);
    }
}

/**
 * Display message in chat
 */
function displayMessage(message, sender) {
    const chatMessages = document.getElementById('chat-messages');
    const messageDiv = document.createElement('div');
    messageDiv.className = `chat-message ${sender}-message`;

    const messageText = document.createElement('div');
    messageText.className = 'message-text';

    // Add '>' prefix for user messages
    if (sender === 'user') {
        messageText.innerHTML = `<strong>> ${message}</strong>`;
    } else {
        messageText.textContent = message;
    }

    messageDiv.appendChild(messageText);
    chatMessages.appendChild(messageDiv);

    // Scroll to bottom
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

/**
 * Show loading indicator (animated dots only, no bubble)
 */
function showLoadingIndicator() {
    const chatMessages = document.getElementById('chat-messages');
    const loadingDiv = document.createElement('div');
    loadingDiv.className = 'loading-indicator';
    loadingDiv.id = 'loading-indicator';
    loadingDiv.innerHTML = '<span>.</span><span>.</span><span>.</span>';

    chatMessages.appendChild(loadingDiv);

    // Scroll to bottom
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

/**
 * Remove loading indicator
 */
function removeLoadingIndicator() {
    const loadingIndicator = document.getElementById('loading-indicator');
    if (loadingIndicator) {
        loadingIndicator.remove();
    }
}

/**
 * Display product table (compact format)
 */
function displayProductCards(products) {
    const chatMessages = document.getElementById('chat-messages');
    const tableContainer = document.createElement('div');
    tableContainer.className = 'chat-products-table-container';

    const table = document.createElement('table');
    table.className = 'chat-products-table';

    // Table header
    const thead = document.createElement('thead');
    thead.innerHTML = `
        <tr>
            <th>Product</th>
            <th>Price</th>
            <th>Origin</th>
            <th>Roast</th>
            <th>Flavors</th>
        </tr>
    `;
    table.appendChild(thead);

    // Table body
    const tbody = document.createElement('tbody');
    products.forEach(product => {
        const row = createChatProductRow(product);
        tbody.appendChild(row);
    });
    table.appendChild(tbody);

    tableContainer.appendChild(table);
    chatMessages.appendChild(tableContainer);

    // Don't auto-scroll - let users see products without scrolling back up
}

/**
 * Display bot response with product recommendations
 */
function displayBotResponse(data) {
    const chatMessages = document.getElementById('chat-messages');

    // Check if this is a clarifying question (bot needs more info)
    if (data.clarifyingQuestion) {
        // Display clarifying question with special styling
        displayClarifyingQuestion(data.clarifyingQuestion);

        // Display clarifying action buttons prominently
        if (data.suggestedActions && data.suggestedActions.length > 0) {
            displayClarifyingActions(data.suggestedActions);
        }
        return; // Don't show regular content for clarifying questions
    }

    // Display explanation
    if (data.explanation) {
        displayMessage(data.explanation, 'bot');
    }

    // Display product recommendations
    if (data.products && data.products.length > 0) {
        displayProductCards(data.products);
    }

    // Display suggested actions (Grok-generated quick action buttons)
    if (data.suggestedActions && data.suggestedActions.length > 0) {
        const actionsContainer = document.createElement('div');
        actionsContainer.className = 'chat-quick-actions';

        const actionsLabel = document.createElement('div');
        actionsLabel.className = 'quick-actions-label';
        actionsLabel.textContent = 'Quick actions:';
        actionsContainer.appendChild(actionsLabel);

        const actionsRow = document.createElement('div');
        actionsRow.className = 'quick-actions-row';

        data.suggestedActions.forEach(action => {
            const actionButton = createQuickActionButton(action);
            actionsRow.appendChild(actionButton);
        });

        actionsContainer.appendChild(actionsRow);
        chatMessages.appendChild(actionsContainer);
    }

    // Display error if any
    if (data.error) {
        console.error('Chatbot error:', data.error);
    }
}

/**
 * Display clarifying question with barista-style formatting
 */
function displayClarifyingQuestion(question) {
    const chatMessages = document.getElementById('chat-messages');
    const messageDiv = document.createElement('div');
    messageDiv.className = 'chat-message bot-message clarifying-question';

    const messageText = document.createElement('div');
    messageText.className = 'message-text';
    messageText.innerHTML = `<span class="clarify-icon">🤔</span> ${question}`;

    messageDiv.appendChild(messageText);
    chatMessages.appendChild(messageDiv);

    // Scroll to bottom
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

/**
 * Display clarifying action buttons prominently (grid layout)
 * Used when bot needs more info from user (e.g., budget, flavor preference)
 */
function displayClarifyingActions(actions) {
    const chatMessages = document.getElementById('chat-messages');
    const actionsContainer = document.createElement('div');
    actionsContainer.className = 'chat-clarifying-actions';

    actions.forEach(action => {
        const button = document.createElement('button');
        button.className = 'clarifying-action-btn';

        // Add icon if provided
        if (action.icon) {
            button.innerHTML = `<span class="clarify-btn-icon">${action.icon}</span><span class="clarify-btn-label">${action.label}</span>`;
        } else {
            button.textContent = action.label;
        }

        button.title = action.intent;

        button.onclick = () => {
            // Pre-fill chat input with the action label or intent
            const chatInput = document.getElementById('chat-input');
            const query = action.label; // Use label directly for clarifying responses
            chatInput.value = query;
            chatInput.focus();

            // Auto-send the message
            sendMessage();
        };

        actionsContainer.appendChild(button);
    });

    chatMessages.appendChild(actionsContainer);

    // Scroll to bottom
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

/**
 * Create quick action button from Grok suggestion
 */
function createQuickActionButton(action) {
    const button = document.createElement('button');
    button.className = 'quick-action-btn';

    // Add icon if provided
    if (action.icon) {
        button.textContent = `${action.icon} ${action.label}`;
    } else {
        button.textContent = action.label;
    }

    button.title = action.intent;

    button.onclick = () => {
        // Pre-fill chat input with the action intent converted to natural language
        const chatInput = document.getElementById('chat-input');
        const intentToQuery = convertIntentToQuery(action.intent);
        chatInput.value = intentToQuery;
        chatInput.focus();

        // Auto-send the message
        sendMessage();
    };

    return button;
}

/**
 * Convert intent to natural language query
 */
function convertIntentToQuery(intent) {
    const intentMap = {
        // SCA flavor profile queries (MORE_CATEGORY)
        'more_roasted': 'Show me something more roasted/bitter',
        'more_fruity': 'Show me something more fruity',
        'more_sweet': 'Show me something sweeter',
        'more_floral': 'Show me something more floral',
        'more_nutty': 'Show me something more nutty',
        'more_spicy': 'Show me something more spicy',

        // Character axes queries (MORE_CHARACTER / LESS_CHARACTER)
        'more_acidity': 'Show me something with more acidity',
        'less_acidity': 'Show me something with less acidity',
        'more_body': 'Show me something with fuller body',
        'less_body': 'Show me something with lighter body',
        'more_complexity': 'Show me something more complex',
        'less_complexity': 'Show me something simpler/cleaner',

        // Graph-based queries
        'same_origin': 'Show me products from the same origin',
        'same_roast': 'Show me products with the same roast level',
        'same_process': 'Show me products with the same processing method',
        'similar_flavors': 'Show me products with similar flavors',
        'similar_profile': 'Show me products with similar direction (similar acidity, body, and roast character)',
        'lighter_roast': 'Show me lighter roasts',
        'darker_roast': 'Show me darker roasts',
        'cheaper': 'Show me cheaper options',
        'explore_ethiopian': 'Show me Ethiopian coffees',
        'explore_natural': 'Show me Natural process coffees'
    };

    return intentMap[intent] || intent.replace(/_/g, ' ');
}

/**
 * Create compact table row for chatbot product recommendations
 * Returns a DocumentFragment containing both the product row and optional reason row
 */
function createChatProductRow(product) {
    // Create a fragment to hold both rows
    const fragment = document.createDocumentFragment();

    // Main product row
    const row = document.createElement('tr');
    row.className = 'chat-product-row';

    // Product name + brand column (WITHOUT reason - reason goes in separate row)
    const productCell = document.createElement('td');
    productCell.className = 'product-name-cell';

    let cellContent = '';
    if (product.id) {
        cellContent = `<a href="/product-detail.html?id=${product.id}" class="product-name-link" target="_blank">${product.name}</a>`;
    } else {
        cellContent = `<span class="product-name-text">${product.name}</span>`;
    }
    cellContent += `<br><small class="brand-name">${product.brand}</small>`;

    productCell.innerHTML = cellContent;
    row.appendChild(productCell);

    // Price column (with variants fallback)
    const priceCell = document.createElement('td');
    priceCell.className = 'price-cell';
    const currency = product.currency || 'GBP';
    if (product.priceVariants && product.priceVariants.length > 0) {
        priceCell.textContent = product.priceVariants
            .map(v => `${v.size}: ${formatPrice(v.price, currency)}`)
            .join(' | ');
    } else if (product.price) {
        priceCell.textContent = formatPrice(product.price, currency);
    } else {
        priceCell.textContent = 'N/A';
    }
    row.appendChild(priceCell);

    // Origin column
    const originCell = document.createElement('td');
    originCell.className = 'origin-cell';
    originCell.textContent = product.origin || 'Unknown';
    row.appendChild(originCell);

    // Roast level column
    const roastCell = document.createElement('td');
    roastCell.className = 'roast-cell';
    roastCell.textContent = product.roastLevel || 'Unknown';
    row.appendChild(roastCell);

    // Flavors column (max 3 flavors, truncate with ellipsis)
    const flavorsCell = document.createElement('td');
    flavorsCell.className = 'flavors-cell';
    if (product.flavors && product.flavors.length > 0) {
        const flavorText = product.flavors.slice(0, 3).join(', ');
        const hasMore = product.flavors.length > 3;
        flavorsCell.textContent = hasMore ? `${flavorText}...` : flavorText;
        flavorsCell.title = product.flavors.join(', '); // Full list on hover
    } else {
        flavorsCell.textContent = '-';
    }
    row.appendChild(flavorsCell);

    fragment.appendChild(row);

    // Reason row (spans all columns) - SEPARATE ROW for better readability
    if (product.reason) {
        const reasonRow = document.createElement('tr');
        reasonRow.className = 'chat-product-reason-row';

        const reasonCell = document.createElement('td');
        reasonCell.colSpan = 5; // Spans all 5 columns
        reasonCell.className = 'product-reason-cell';
        reasonCell.innerHTML = `<small class="product-reason-text">💡 ${product.reason}</small>`;

        reasonRow.appendChild(reasonCell);
        fragment.appendChild(reasonRow);
    }

    return fragment;
}

/**
 * Legacy function name for compatibility - now creates table row
 */
function createChatProductCard(product) {
    return createChatProductRow(product);
}

/**
 * Set reference product and ask for similar
 */
function setReferenceProductAndAsk(productId, productName) {
    referenceProductId = productId;
    const chatInput = document.getElementById('chat-input');
    chatInput.value = `Show me something similar to "${productName}"`;
    chatInput.focus();
}

/**
 * Set reference product (called from external pages like brands.html)
 */
function setReferenceProduct(productId, productName) {
    referenceProductId = productId;
    saveStateToStorage();
    console.log('Reference product set:', productId, productName);

    // Pre-fill chat input
    const chatInput = document.getElementById('chat-input');
    if (chatInput) {
        chatInput.value = `Tell me more about "${productName}"`;
        chatInput.focus();
    }

    // Display context message
    displayMessage(`Reference product set: ${productName}`, 'system');
}

/**
 * Clear conversation (client-side only)
 */
function clearConversation() {
    // Reset state
    conversationHistory = [];
    shownProductIds = [];
    referenceProductId = null;

    // Clear localStorage
    try {
        localStorage.removeItem(STORAGE_KEY_CONVERSATION);
        localStorage.removeItem(STORAGE_KEY_SHOWN_PRODUCTS);
        localStorage.removeItem(STORAGE_KEY_REFERENCE_PRODUCT);
        console.log('Conversation cleared from localStorage');
    } catch (err) {
        console.error('Failed to clear localStorage:', err);
    }

    // Clear UI
    const chatMessages = document.getElementById('chat-messages');
    chatMessages.innerHTML = '';
    displayMessage('How can I help you find your perfect coffee?', 'bot');
}

/**
 * Update send button state
 */
function updateSendButton(isLoading) {
    const sendBtn = document.getElementById('chat-send-btn');
    if (sendBtn) {
        sendBtn.disabled = isLoading;
        const btnText = sendBtn.querySelector('.btn-text');
        const btnIcon = sendBtn.querySelector('.btn-icon');

        if (btnText) {
            // Main chat page (products.html) with separate text/icon
            btnText.textContent = isLoading ? 'Thinking...' : 'Send';
            if (btnIcon) {
                btnIcon.style.display = isLoading ? 'none' : 'inline';
            }
        } else {
            // Inline chat (brands.html) - simple button
            sendBtn.textContent = isLoading ? 'Thinking...' : 'Send';
        }
    }
}

/**
 * Log chat question for analytics
 */
async function logChatQuestion(question) {
    try {
        await fetch('/api/analytics/log', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                actionType: 'chat_question',
                metadata: JSON.stringify({ question: question.substring(0, 500) })
            })
        });
    } catch (e) {
        console.debug('Analytics log failed:', e);
    }
}

/**
 * Log chat answer for analytics (tracks brand appearances in recommendations)
 */
async function logChatAnswer(products) {
    try {
        // Extract product and brand IDs
        const productIds = products.map(p => p.id);
        const brandIds = [...new Set(products.map(p => p.brandId).filter(id => id))];

        // Log each brand that appeared in recommendations
        for (const brandId of brandIds) {
            await fetch('/api/analytics/log', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    actionType: 'chat_answer',
                    brandId: brandId,
                    metadata: JSON.stringify({
                        totalProducts: productIds.length,
                        productIds: productIds
                    })
                })
            });
        }
    } catch (e) {
        console.debug('Analytics log failed:', e);
    }
}

// ========================================
// Personalized Recommendations Functions
// ========================================

// User preferences cache
let userPreferences = {
    loved: [],      // Products user loves
    pinned: [],     // Products user wants to try
    disliked: []    // Products to avoid
};

/**
 * Load user preferences from tracking API
 * Returns promise with preferences or null if not signed in
 */
async function loadUserPreferences() {
    if (typeof currentUser === 'undefined' || !currentUser) {
        return null;
    }

    try {
        const [lovedRes, pinnedRes, dislikedRes] = await Promise.all([
            fetch('/api/user/tracking?status=LOVE'),
            fetch('/api/user/tracking?status=WANT'),
            fetch('/api/user/tracking?status=DISLIKE')
        ]);

        if (lovedRes.ok && pinnedRes.ok && dislikedRes.ok) {
            const loved = await lovedRes.json();
            const pinned = await pinnedRes.json();
            const disliked = await dislikedRes.json();

            // Filter to only include items marked for chat (includedInChat !== false)
            const filterForChat = (items) => items.filter(item => item.includedInChat !== false);

            userPreferences = {
                loved: filterForChat(loved).slice(0, 10),      // Max 10 most recent
                pinned: filterForChat(pinned).slice(0, 10),    // Max 10 most recent
                disliked: filterForChat(disliked)              // Include all for negative filtering
            };

            console.log('User preferences loaded (filtered by includedInChat):', {
                loved: userPreferences.loved.length,
                pinned: userPreferences.pinned.length,
                disliked: userPreferences.disliked.length
            });

            return userPreferences;
        }
    } catch (e) {
        console.debug('Failed to load user preferences:', e);
    }

    return null;
}

/**
 * Check and show personalized actions if user is signed in with enough tracked products
 */
async function checkPersonalizedActions() {
    const actionsDiv = document.getElementById('personalized-actions');
    if (!actionsDiv) return;

    // Hide by default
    actionsDiv.classList.add('hidden');

    // Only show if user is signed in
    if (typeof currentUser === 'undefined' || !currentUser) {
        return;
    }

    // Only show if conversation is empty (first message)
    if (conversationHistory.length > 0) {
        return;
    }

    // Load preferences
    const prefs = await loadUserPreferences();
    if (!prefs) return;

    // Check minimum product count (3 for each type)
    const hasEnoughLoved = prefs.loved.length >= 3;
    const hasEnoughPinned = prefs.pinned.length >= 3;

    if (!hasEnoughLoved && !hasEnoughPinned) {
        return; // Not enough tracked products
    }

    // Show/hide individual buttons based on tracked count
    const btnLoved = document.getElementById('btn-find-loved');
    const btnPinned = document.getElementById('btn-find-pinned');

    if (btnLoved) {
        btnLoved.style.display = hasEnoughLoved ? 'flex' : 'none';
    }
    if (btnPinned) {
        btnPinned.style.display = hasEnoughPinned ? 'flex' : 'none';
    }

    // Show the personalized actions section
    actionsDiv.classList.remove('hidden');
}

/**
 * Find coffees similar to user's loved products
 */
async function findSimilarToLoved() {
    if (userPreferences.loved.length === 0) {
        displayMessage('Please add some coffees to your "Love" list first!', 'bot');
        return;
    }

    // Hide personalized actions after clicking
    const actionsDiv = document.getElementById('personalized-actions');
    if (actionsDiv) {
        actionsDiv.classList.add('hidden');
    }

    // Build query with user context
    const lovedIds = userPreferences.loved.map(p => p.productId);
    const dislikedIds = userPreferences.disliked.map(p => p.productId);

    // Display user message
    const query = 'Find coffees similar to the ones I love';
    displayMessage(query, 'user');
    conversationHistory.push({ role: 'user', content: query });

    // Show loading
    showLoadingIndicator();
    isWaitingForResponse = true;
    updateSendButton(true);

    try {
        const request = {
            query: query,
            messages: conversationHistory,
            shownProductIds: shownProductIds,
            referenceProductId: referenceProductId,
            lovedProductIds: lovedIds,
            dislikedProductIds: dislikedIds
        };

        const response = await fetch(`${CHATBOT_API_BASE}/query`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(request)
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        // Add to history
        conversationHistory.push({
            role: 'assistant',
            content: data.explanation,
            products: data.products || []
        });

        // Track shown products
        if (data.products && data.products.length > 0) {
            data.products.forEach(p => {
                if (!shownProductIds.includes(p.id)) {
                    shownProductIds.push(p.id);
                }
            });
            if (!referenceProductId) {
                referenceProductId = data.products[0].id;
            }
        }

        saveStateToStorage();
        incrementAnonymousChatCount();
        removeLoadingIndicator();
        displayBotResponse(data);

    } catch (err) {
        console.error('Error:', err);
        removeLoadingIndicator();
        displayMessage('Sorry, I encountered an error. Please try again.', 'bot');
    } finally {
        isWaitingForResponse = false;
        updateSendButton(false);
    }
}

/**
 * Find coffees similar to user's pinned (want to try) products
 */
async function findSimilarToPinned() {
    if (userPreferences.pinned.length === 0) {
        displayMessage('Please add some coffees to your "Want to Try" list first!', 'bot');
        return;
    }

    // Hide personalized actions after clicking
    const actionsDiv = document.getElementById('personalized-actions');
    if (actionsDiv) {
        actionsDiv.classList.add('hidden');
    }

    // Build query with user context
    const pinnedIds = userPreferences.pinned.map(p => p.productId);
    const dislikedIds = userPreferences.disliked.map(p => p.productId);

    // Display user message
    const query = 'Find coffees similar to the ones I want to try';
    displayMessage(query, 'user');
    conversationHistory.push({ role: 'user', content: query });

    // Show loading
    showLoadingIndicator();
    isWaitingForResponse = true;
    updateSendButton(true);

    try {
        const request = {
            query: query,
            messages: conversationHistory,
            shownProductIds: shownProductIds,
            referenceProductId: referenceProductId,
            lovedProductIds: pinnedIds,  // Use pinned as positive reference
            dislikedProductIds: dislikedIds
        };

        const response = await fetch(`${CHATBOT_API_BASE}/query`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(request)
        });

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        // Add to history
        conversationHistory.push({
            role: 'assistant',
            content: data.explanation,
            products: data.products || []
        });

        // Track shown products
        if (data.products && data.products.length > 0) {
            data.products.forEach(p => {
                if (!shownProductIds.includes(p.id)) {
                    shownProductIds.push(p.id);
                }
            });
            if (!referenceProductId) {
                referenceProductId = data.products[0].id;
            }
        }

        saveStateToStorage();
        incrementAnonymousChatCount();
        removeLoadingIndicator();
        displayBotResponse(data);

    } catch (err) {
        console.error('Error:', err);
        removeLoadingIndicator();
        displayMessage('Sorry, I encountered an error. Please try again.', 'bot');
    } finally {
        isWaitingForResponse = false;
        updateSendButton(false);
    }
}

/**
 * Auto-initialize on page load
 */
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initChatbot);
} else {
    initChatbot();
}
