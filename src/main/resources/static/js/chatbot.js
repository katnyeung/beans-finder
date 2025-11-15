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

/**
 * Initialize chatbot
 */
function initChatbot() {
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

    console.log('Chatbot initialized. History length:', conversationHistory.length);
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
 * Send message to chatbot
 */
async function sendMessage() {
    const chatInput = document.getElementById('chat-input');
    const query = chatInput.value.trim();

    if (!query || isWaitingForResponse) {
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

    // Clear input
    chatInput.value = '';
    isWaitingForResponse = true;
    updateSendButton(true);

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

        // Display bot response
        displayBotResponse(data);

    } catch (err) {
        console.error('Error:', err);
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
        'more_roasted': 'Show me something more bitter',
        'more_fruity': 'Show me something more fruity',
        'more_sweet': 'Show me something sweeter',
        'more_sour': 'Show me something more acidic',
        'same_origin': 'Show me products from the same origin',
        'same_roast': 'Show me products with the same roast level',
        'same_process': 'Show me products with the same processing method',
        'similar_flavors': 'Show me products with similar flavors',
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
 */
function createChatProductRow(product) {
    const row = document.createElement('tr');
    row.className = 'chat-product-row';

    // Product name + brand + reason column
    const productCell = document.createElement('td');
    productCell.className = 'product-name-cell';

    let cellContent = '';
    if (product.id) {
        cellContent = `<a href="/product-detail.html?id=${product.id}" class="product-name-link">${product.name}</a>`;
    } else {
        cellContent = `<span class="product-name-text">${product.name}</span>`;
    }
    cellContent += `<br><small class="brand-name">${product.brand}</small>`;

    // Add reason if available (critical for understanding Grok's recommendation)
    if (product.reason) {
        cellContent += `<br><small class="product-reason-text">${product.reason}</small>`;
    }

    productCell.innerHTML = cellContent;
    row.appendChild(productCell);

    // Price column
    const priceCell = document.createElement('td');
    priceCell.className = 'price-cell';
    priceCell.textContent = product.price ? `£${product.price.toFixed(2)}` : 'N/A';
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

    return row;
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
 * Auto-initialize on page load
 */
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initChatbot);
} else {
    initChatbot();
}
