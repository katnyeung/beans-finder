#!/bin/bash
# Example API calls for Coffee Database and Knowledge Graph

BASE_URL="http://localhost:8080/api"

echo "========================================"
echo "Coffee Database API Examples"
echo "========================================"
echo ""

# 1. Create a new coffee product
echo "1. Creating/updating coffee product..."
curl -X POST "$BASE_URL/coffee" \
  -H "Content-Type: application/json" \
  -d '{
    "brand": "Sweven Coffee",
    "productName": "El Ocaso - Geisha",
    "url": "https://swevencoffee.co.uk/products/el-ocaso-geisha"
  }' | jq '.'
echo ""
echo ""

# 2. Get all products
echo "2. Getting all products..."
curl -X GET "$BASE_URL/coffee" | jq '.'
echo ""
echo ""

# 3. Get products by brand
echo "3. Getting products by brand (Sweven Coffee)..."
curl -X GET "$BASE_URL/coffee/brand/Sweven%20Coffee" | jq '.'
echo ""
echo ""

# 4. Get in-stock products
echo "4. Getting in-stock products..."
curl -X GET "$BASE_URL/coffee/in-stock" | jq '.'
echo ""
echo ""

# 5. Search products by origin
echo "5. Searching products from Colombia..."
curl -X GET "$BASE_URL/coffee/search?origin=Colombia" | jq '.'
echo ""
echo ""

# 6. Search products by process
echo "6. Searching honey-processed coffees..."
curl -X GET "$BASE_URL/coffee/search?process=Honey" | jq '.'
echo ""
echo ""

# 7. Search products by variety
echo "7. Searching Geisha variety coffees..."
curl -X GET "$BASE_URL/coffee/search?variety=Geisha" | jq '.'
echo ""
echo ""

# 8. Get outdated products (needing update)
echo "8. Getting products needing update (>14 days)..."
curl -X GET "$BASE_URL/coffee/outdated?days=14" | jq '.'
echo ""
echo ""

echo "========================================"
echo "Knowledge Graph API Examples"
echo "========================================"
echo ""

# 9. Find products by SCA category
echo "9. Finding products with 'fruity' notes..."
curl -X GET "$BASE_URL/graph/sca/fruity" | jq '.'
echo ""
echo ""

# 10. Find products by specific flavor
echo "10. Finding products with 'pear' flavor..."
curl -X GET "$BASE_URL/graph/flavor/pear" | jq '.'
echo ""
echo ""

# 11. Find products by variety
echo "11. Finding Geisha variety coffees..."
curl -X GET "$BASE_URL/graph/variety/Geisha" | jq '.'
echo ""
echo ""

# 12. Find products by origin
echo "12. Finding Colombian coffees..."
curl -X GET "$BASE_URL/graph/origin/Colombia" | jq '.'
echo ""
echo ""

# 13. Complex query: Process + SCA category
echo "13. Finding honey-processed coffees with fruity notes..."
curl -X GET "$BASE_URL/graph/query?process=Honey&sca=fruity" | jq '.'
echo ""
echo ""

echo "========================================"
echo "Crawler Operations"
echo "========================================"
echo ""

# 14. Trigger manual crawl
echo "14. Triggering manual crawl..."
curl -X POST "$BASE_URL/crawler/trigger" | jq '.'
echo ""
echo ""

echo "All examples completed!"
