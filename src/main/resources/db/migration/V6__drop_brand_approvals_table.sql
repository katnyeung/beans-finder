-- Drop the brand_approvals table
-- The approval workflow has been simplified to just use the 'approved' field in coffee_brands table
DROP TABLE IF EXISTS brand_approvals CASCADE;
