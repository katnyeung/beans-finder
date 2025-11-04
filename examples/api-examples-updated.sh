#!/bin/bash

# Coffee Database & Knowledge Graph - API Examples
# This script demonstrates the main API functionality

BASE_URL="http://localhost:8080"

echo "======================================"
echo "Coffee Database API Examples"
echo "======================================"
echo ""

# 1. Submit a brand for approval
echo "1. Submitting Sweven Coffee for approval..."
curl -X POST "$BASE_URL/api/brands/submit" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Sweven Coffee",
    "website": "https://swevencoffee.co.uk",
    "country": "UK",
    "description": "Specialty coffee roaster focusing on quality and transparency",
    "submittedBy": "admin@example.com",
    "submissionNotes": "High-quality UK roaster with detailed product information"
  }' | jq '.'
echo ""
echo "Press Enter to continue..."
read

# 2. Get pending approvals
echo "2. Getting pending brand approvals..."
PENDING=$(curl -s "$BASE_URL/api/brands/approvals/pending")
echo "$PENDING" | jq '.'
APPROVAL_ID=$(echo "$PENDING" | jq -r '.[0].id')
echo ""
echo "Approval ID: $APPROVAL_ID"
echo "Press Enter to continue..."
read

# 3. Approve the brand
if [ ! -z "$APPROVAL_ID" ] && [ "$APPROVAL_ID" != "null" ]; then
  echo "3. Approving brand..."
  curl -X POST "$BASE_URL/api/brands/approvals/$APPROVAL_ID/approve" \
    -H "Content-Type: application/json" \
    -d '{
      "reviewedBy": "admin@example.com",
      "reviewNotes": "Verified official website and quality standards"
    }'
  echo ""
  echo "Press Enter to continue..."
  read
fi

# 4. Get all approved brands
echo "4. Getting all approved brands..."
BRANDS=$(curl -s "$BASE_URL/api/brands/approved")
echo "$BRANDS" | jq '.'
BRAND_ID=$(echo "$BRANDS" | jq -r '.[0].id')
echo ""
echo "Brand ID: $BRAND_ID"
echo "Press Enter to continue..."
read

# 5. Manually crawl a product
if [ ! -z "$BRAND_ID" ] && [ "$BRAND_ID" != "null" ]; then
  echo "5. Crawling El Ocaso Geisha product..."
  curl -X POST "$BASE_URL/api/products/crawl" \
    -H "Content-Type: application/json" \
    -d "{
      \"brandId\": $BRAND_ID,
      \"productUrl\": \"https://swevencoffee.co.uk/products/el-ocaso-geisha\"
    }" | jq '.'
  echo ""
  echo "Press Enter to continue..."
  read
fi

# 6. Get all products
echo "6. Getting all products..."
curl -s "$BASE_URL/api/products" | jq '.'
echo ""
echo "Press Enter to continue..."
read

# 7. Get products by brand
if [ ! -z "$BRAND_ID" ] && [ "$BRAND_ID" != "null" ]; then
  echo "7. Getting products for brand ID $BRAND_ID..."
  curl -s "$BASE_URL/api/products/brand/$BRAND_ID" | jq '.'
  echo ""
  echo "Press Enter to continue..."
  read
fi

# 8. Initialize SCA categories in Neo4j
echo "8. Initializing SCA categories in knowledge graph..."
curl -X POST "$BASE_URL/api/graph/init-categories"
echo ""
echo "Press Enter to continue..."
read

# 9. Query knowledge graph by flavor
echo "9. Finding products with 'pear' flavor..."
curl -s "$BASE_URL/api/graph/products/flavor/pear" | jq '.'
echo ""
echo "Press Enter to continue..."
read

# 10. Query by SCA category
echo "10. Finding products in 'fruity' SCA category..."
curl -s "$BASE_URL/api/graph/products/sca-category/fruity" | jq '.'
echo ""
echo "Press Enter to continue..."
read

# 11. Query by origin
echo "11. Finding Colombian coffees..."
curl -s "$BASE_URL/api/graph/products/origin/Colombia" | jq '.'
echo ""
echo "Press Enter to continue..."
read

# 12. Complex query: process + flavor
echo "12. Finding honey-processed coffees with pear notes..."
curl -s "$BASE_URL/api/graph/products/complex?process=Honey&flavor=pear" | jq '.'
echo ""
echo "Press Enter to continue..."
read

# 13. Trigger manual crawl
echo "13. Triggering manual crawl of all brands..."
curl -X POST "$BASE_URL/api/crawler/trigger"
echo ""
echo "Press Enter to continue..."
read

echo "======================================"
echo "API Examples Complete!"
echo "======================================"
echo ""
echo "Additional Examples:"
echo ""
echo "# Get in-stock products"
echo "curl $BASE_URL/api/products/in-stock"
echo ""
echo "# Get products by origin"
echo "curl $BASE_URL/api/products/origin/Colombia"
echo ""
echo "# Get products by process"
echo "curl $BASE_URL/api/products/process/Honey"
echo ""
echo "# Get products by variety"
echo "curl $BASE_URL/api/products/variety/Geisha"
echo ""
echo "# Retry failed products"
echo "curl -X POST $BASE_URL/api/crawler/retry-failed"
