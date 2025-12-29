-- Add included_in_chat flag to user_product_tracking table
-- Allows users to mark tracked products to exclude from chatbot suggestions
ALTER TABLE user_product_tracking
ADD COLUMN included_in_chat BOOLEAN NOT NULL DEFAULT TRUE;
