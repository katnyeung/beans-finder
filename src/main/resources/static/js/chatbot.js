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
    messageText.textContent = message;

    messageDiv.appendChild(messageText);
    chatMessages.appendChild(messageDiv);

    // Scroll to bottom
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

/**
 * Display product cards (helper function for displaying products)
 */
function displayProductCards(products) {
    const chatMessages = document.getElementById('chat-messages');
    const productsContainer = document.createElement('div');
    productsContainer.className = 'chat-products';

    products.forEach(product => {
        const productCard = createChatProductCard(product);
        productsContainer.appendChild(productCard);
    });

    chatMessages.appendChild(productsContainer);

    // Scroll to bottom
    chatMessages.scrollTop = chatMessages.scrollHeight;
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

        // Scroll to bottom
        chatMessages.scrollTop = chatMessages.scrollHeight;
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
 * Create product card for chat
 */
function createChatProductCard(product) {
    const card = document.createElement('div');
    card.className = 'chat-product-card';

    // Product name + brand (name is clickable link)
    const title = document.createElement('div');
    title.className = 'product-title';
    if (product.url) {
        title.innerHTML = `<a href="${product.url}" target="_blank" class="product-name-link"><strong>${product.name}</strong></a><br><small>by ${product.brand}</small>`;
    } else {
        title.innerHTML = `<strong>${product.name}</strong><br><small>by ${product.brand}</small>`;
    }
    card.appendChild(title);

    // Price
    const price = document.createElement('div');
    price.className = 'product-price';
    price.textContent = product.price
        ? `£${product.price.toFixed(2)}`
        : 'Price not available';
    card.appendChild(price);

    // Flavors
    if (product.flavors && product.flavors.length > 0) {
        const flavors = document.createElement('div');
        flavors.className = 'product-flavors';
        flavors.textContent = product.flavors.slice(0, 5).join(', ');
        card.appendChild(flavors);
    }

    // Origin + Roast
    const metadata = document.createElement('div');
    metadata.className = 'product-metadata';
    metadata.innerHTML = `
        <small>
            ${product.origin || 'Unknown origin'} ·
            ${product.roastLevel || 'Unknown roast'}
        </small>
    `;
    card.appendChild(metadata);

    // Reason for recommendation
    if (product.reason) {
        const reason = document.createElement('div');
        reason.className = 'product-reason';
        reason.innerHTML = `<em>${product.reason}</em>`;
        card.appendChild(reason);
    }

    // Actions
    const actions = document.createElement('div');
    actions.className = 'product-actions';

    // "Find similar" button (only button needed, name is already a link)
    const similarBtn = document.createElement('button');
    similarBtn.className = 'btn-similar';
    similarBtn.textContent = 'Find Similar';
    similarBtn.onclick = () => setReferenceProductAndAsk(product.id, product.name);
    actions.appendChild(similarBtn);

    card.appendChild(actions);

    return card;
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
