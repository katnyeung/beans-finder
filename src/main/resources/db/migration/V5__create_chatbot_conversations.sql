-- Create chatbot conversations table for persistent storage
CREATE TABLE chatbot_conversations (
    conversation_id VARCHAR(255) PRIMARY KEY,
    reference_product_id BIGINT,
    messages JSONB NOT NULL DEFAULT '[]'::jsonb,
    shown_product_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Foreign key to products table
    CONSTRAINT fk_reference_product
        FOREIGN KEY (reference_product_id)
        REFERENCES coffee_products(id)
        ON DELETE SET NULL
);

-- Index for faster lookups by last activity (for cleanup of old conversations)
CREATE INDEX idx_chatbot_conversations_last_activity
    ON chatbot_conversations(last_activity_at);

-- Index for product lookups
CREATE INDEX idx_chatbot_conversations_reference_product
    ON chatbot_conversations(reference_product_id)
    WHERE reference_product_id IS NOT NULL;

-- Comment on table
COMMENT ON TABLE chatbot_conversations IS 'Stores persistent chatbot conversation state for multi-turn RAG-powered conversations with Grok LLM';
COMMENT ON COLUMN chatbot_conversations.messages IS 'JSONB array of conversation messages with role (user/assistant) and content';
COMMENT ON COLUMN chatbot_conversations.shown_product_ids IS 'JSONB array of product IDs already recommended to avoid duplicates';
