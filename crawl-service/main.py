"""
Graphee.link Crawl Service
==========================
A Python FastAPI service providing web crawling capabilities using:
- Playwright (raw browser automation)
- Crawl4AI (LLM-friendly markdown extraction)

Port: 8081
Endpoints:
- POST /crawl/playwright   - Raw Playwright crawl
- POST /crawl/crawl4ai     - Crawl4AI with clean markdown
- POST /crawl/compare      - AB test both methods
- POST /crawl/smart        - Auto-select best method with fallback
- GET  /health             - Health check
"""

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from playwright.async_api import async_playwright
from crawl4ai import AsyncWebCrawler, BrowserConfig, CrawlerRunConfig
from contextlib import asynccontextmanager
import asyncio
import logging
import time
import re
from typing import Optional
from enum import Enum

# Logging Setup
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger("graphee-crawl")

# App Lifespan (startup/shutdown)
@asynccontextmanager
async def lifespan(app: FastAPI):
    """Handle startup and shutdown events"""
    logger.info("Graphee Crawl Service starting on port 8081...")
    logger.info("Initializing browser engines...")
    yield
    logger.info("Graphee Crawl Service shutting down...")

app = FastAPI(
    title="Graphee Crawl Service",
    description="Web crawling service for Graphee.link coffee discovery platform",
    version="1.0.0",
    lifespan=lifespan
)

# CORS - adjust origins for your Spring Boot app
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Models
class CrawlMethod(str, Enum):
    PLAYWRIGHT = "playwright"
    CRAWL4AI = "crawl4ai"

class CrawlRequest(BaseModel):
    """Request model for crawl endpoints"""
    url: str = Field(..., description="URL to crawl")
    wait_for: Optional[str] = Field(None, description="CSS selector to wait for before extracting")
    wait_timeout: int = Field(30000, description="Timeout in milliseconds")
    max_content_length: int = Field(15000, description="Max chars to return (filters noise)")

    model_config = {
        "json_schema_extra": {
            "examples": [
                {
                    "url": "https://example-coffee.com/products/ethiopia-natural",
                    "wait_for": ".product-description",
                    "wait_timeout": 30000
                }
            ]
        }
    }

class CrawlResponse(BaseModel):
    """Response model for crawl endpoints"""
    url: str
    method: str
    success: bool
    markdown: Optional[str] = None
    html: Optional[str] = None
    cleaned_html: Optional[str] = None
    links: Optional[list[str]] = None
    title: Optional[str] = None
    error: Optional[str] = None
    duration_ms: int = 0
    content_length: int = 0

class CompareResponse(BaseModel):
    """Response model for AB test comparison"""
    url: str
    playwright: CrawlResponse
    crawl4ai: CrawlResponse
    recommendation: str
    reasoning: str

class HealthResponse(BaseModel):
    """Health check response"""
    status: str
    service: str
    version: str
    playwright_ready: bool
    crawl4ai_ready: bool


# Endpoint: Health Check
@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Check service health and dependencies"""
    playwright_ok = True
    crawl4ai_ok = True

    # Quick Playwright check
    try:
        async with async_playwright() as p:
            browser = await p.chromium.launch(headless=True)
            await browser.close()
    except Exception as e:
        logger.error(f"Playwright health check failed: {e}")
        playwright_ok = False

    # Quick Crawl4AI check
    try:
        async with AsyncWebCrawler() as crawler:
            pass
    except Exception as e:
        logger.error(f"Crawl4AI health check failed: {e}")
        crawl4ai_ok = False

    return HealthResponse(
        status="healthy" if (playwright_ok and crawl4ai_ok) else "degraded",
        service="graphee-crawl-service",
        version="1.0.0",
        playwright_ready=playwright_ok,
        crawl4ai_ready=crawl4ai_ok
    )


# Endpoint: Raw Playwright Crawl
@app.post("/crawl/playwright", response_model=CrawlResponse)
async def crawl_playwright(req: CrawlRequest):
    """
    Crawl using raw Playwright browser automation.
    Returns raw HTML content.
    """
    start_time = time.time()
    logger.info(f"[Playwright] Starting crawl: {req.url}")

    try:
        async with async_playwright() as p:
            browser = await p.chromium.launch(
                headless=True,
                args=[
                    '--no-sandbox',
                    '--disable-setuid-sandbox',
                    '--disable-dev-shm-usage',
                    '--disable-gpu'
                ]
            )

            context = await browser.new_context(
                viewport={'width': 1920, 'height': 1080},
                user_agent='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            )

            page = await context.new_page()

            # Navigate
            await page.goto(req.url, wait_until="networkidle", timeout=req.wait_timeout)

            # Wait for specific element if requested
            if req.wait_for:
                try:
                    await page.wait_for_selector(req.wait_for, timeout=req.wait_timeout)
                except Exception as e:
                    logger.warning(f"[Playwright] wait_for selector timeout: {req.wait_for}")

            # Extract content
            html = await page.content()
            title = await page.title()

            # Extract all links
            links = await page.eval_on_selector_all('a[href]', 'elements => elements.map(e => e.href)')

            await browser.close()

            duration = int((time.time() - start_time) * 1000)
            logger.info(f"[Playwright] Completed in {duration}ms: {req.url}")

            return CrawlResponse(
                url=req.url,
                method="playwright",
                success=True,
                html=html,
                title=title,
                links=links[:100],
                duration_ms=duration,
                content_length=len(html)
            )

    except Exception as e:
        duration = int((time.time() - start_time) * 1000)
        logger.error(f"[Playwright] Failed after {duration}ms: {req.url} - {str(e)}")
        return CrawlResponse(
            url=req.url,
            method="playwright",
            success=False,
            error=str(e),
            duration_ms=duration
        )


# Endpoint: Crawl4AI Crawl
@app.post("/crawl/crawl4ai", response_model=CrawlResponse)
async def crawl_crawl4ai(req: CrawlRequest):
    """
    Crawl using Crawl4AI with LLM-friendly markdown output.
    Returns clean markdown, cleaned HTML.
    """
    start_time = time.time()
    logger.info(f"[Crawl4AI] Starting crawl: {req.url}")

    try:
        # Browser configuration
        browser_config = BrowserConfig(
            headless=True,
            verbose=False,
            extra_args=[
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-dev-shm-usage'
            ]
        )

        # Crawler run configuration
        crawler_config = CrawlerRunConfig(
            wait_for=f"css:{req.wait_for}" if req.wait_for else None,
            page_timeout=req.wait_timeout,
        )

        async with AsyncWebCrawler(config=browser_config) as crawler:
            result = await crawler.arun(
                url=req.url,
                config=crawler_config
            )

            duration = int((time.time() - start_time) * 1000)

            if result.success:
                logger.info(f"[Crawl4AI] Completed in {duration}ms: {req.url}")

                # Extract href strings from link objects (Crawl4AI returns dicts with href, total_score, etc.)
                extracted_links = None
                if result.links:
                    internal_links = result.links.get("internal", [])
                    extracted_links = []
                    for link in internal_links[:100]:
                        if isinstance(link, dict) and "href" in link:
                            extracted_links.append(link["href"])
                        elif isinstance(link, str):
                            extracted_links.append(link)

                # Filter markdown to extract only product-relevant content
                filtered_markdown = _extract_product_content(
                    result.markdown,
                    max_length=req.max_content_length
                )

                return CrawlResponse(
                    url=req.url,
                    method="crawl4ai",
                    success=True,
                    markdown=filtered_markdown,
                    html=result.html,
                    cleaned_html=result.cleaned_html,
                    links=extracted_links,
                    title=result.metadata.get("title") if result.metadata else None,
                    duration_ms=duration,
                    content_length=len(filtered_markdown) if filtered_markdown else 0
                )
            else:
                logger.warning(f"[Crawl4AI] Crawl unsuccessful: {req.url}")
                return CrawlResponse(
                    url=req.url,
                    method="crawl4ai",
                    success=False,
                    error=result.error_message if hasattr(result, 'error_message') else "Unknown error",
                    duration_ms=duration
                )

    except Exception as e:
        duration = int((time.time() - start_time) * 1000)
        logger.error(f"[Crawl4AI] Failed after {duration}ms: {req.url} - {str(e)}")
        return CrawlResponse(
            url=req.url,
            method="crawl4ai",
            success=False,
            error=str(e),
            duration_ms=duration
        )


# Endpoint: Compare (AB Test)
@app.post("/crawl/compare", response_model=CompareResponse)
async def crawl_compare(req: CrawlRequest):
    """
    Run both Playwright and Crawl4AI on the same URL.
    Returns comparison with recommendation.
    """
    logger.info(f"[Compare] Starting AB test: {req.url}")

    # Run both concurrently
    playwright_task = crawl_playwright(req)
    crawl4ai_task = crawl_crawl4ai(req)

    playwright_result, crawl4ai_result = await asyncio.gather(
        playwright_task,
        crawl4ai_task,
        return_exceptions=True
    )

    # Handle exceptions
    if isinstance(playwright_result, Exception):
        playwright_result = CrawlResponse(
            url=req.url,
            method="playwright",
            success=False,
            error=str(playwright_result)
        )

    if isinstance(crawl4ai_result, Exception):
        crawl4ai_result = CrawlResponse(
            url=req.url,
            method="crawl4ai",
            success=False,
            error=str(crawl4ai_result)
        )

    # Determine recommendation
    recommendation, reasoning = _evaluate_results(playwright_result, crawl4ai_result)

    logger.info(f"[Compare] Recommendation for {req.url}: {recommendation}")

    return CompareResponse(
        url=req.url,
        playwright=playwright_result,
        crawl4ai=crawl4ai_result,
        recommendation=recommendation,
        reasoning=reasoning
    )

def _evaluate_results(playwright: CrawlResponse, crawl4ai: CrawlResponse) -> tuple[str, str]:
    """Evaluate which crawl method performed better"""

    # Both failed
    if not playwright.success and not crawl4ai.success:
        return "none", "Both methods failed to crawl the page"

    # Only one succeeded
    if playwright.success and not crawl4ai.success:
        return "playwright", "Only Playwright succeeded"
    if crawl4ai.success and not playwright.success:
        return "crawl4ai", "Only Crawl4AI succeeded"

    # Both succeeded - compare quality
    reasons = []
    playwright_score = 0
    crawl4ai_score = 0

    # Content length check
    if crawl4ai.markdown and len(crawl4ai.markdown) > 500:
        crawl4ai_score += 2
        reasons.append("Crawl4AI produced substantial markdown")

    # Speed comparison
    if playwright.duration_ms < crawl4ai.duration_ms * 0.7:
        playwright_score += 1
        reasons.append(f"Playwright faster ({playwright.duration_ms}ms vs {crawl4ai.duration_ms}ms)")
    elif crawl4ai.duration_ms < playwright.duration_ms * 0.7:
        crawl4ai_score += 1
        reasons.append(f"Crawl4AI faster ({crawl4ai.duration_ms}ms vs {playwright.duration_ms}ms)")

    # For AI/vector search use cases, markdown is preferred
    crawl4ai_score += 1
    reasons.append("Crawl4AI markdown better for LLM extraction")

    if crawl4ai_score > playwright_score:
        return "crawl4ai", "; ".join(reasons)
    elif playwright_score > crawl4ai_score:
        return "playwright", "; ".join(reasons)
    else:
        return "crawl4ai", "Tie - defaulting to Crawl4AI for better AI compatibility; " + "; ".join(reasons)


# Endpoint: Smart Crawl (with fallback)
@app.post("/crawl/smart", response_model=CrawlResponse)
async def crawl_smart(req: CrawlRequest, prefer: CrawlMethod = CrawlMethod.CRAWL4AI):
    """
    Smart crawl with automatic fallback.
    Tries preferred method first, falls back to alternative if failed or poor quality.
    """
    logger.info(f"[Smart] Starting with {prefer}: {req.url}")

    if prefer == CrawlMethod.CRAWL4AI:
        primary_fn = crawl_crawl4ai
        fallback_fn = crawl_playwright
        fallback_name = "playwright"
    else:
        primary_fn = crawl_playwright
        fallback_fn = crawl_crawl4ai
        fallback_name = "crawl4ai"

    # Try primary method
    result = await primary_fn(req)

    # Check if we need fallback
    needs_fallback = False
    if not result.success:
        needs_fallback = True
        logger.info(f"[Smart] Primary method failed, trying fallback")
    elif not _is_quality_content(result):
        needs_fallback = True
        logger.info(f"[Smart] Primary method poor quality, trying fallback")

    if needs_fallback:
        fallback_result = await fallback_fn(req)
        if fallback_result.success and _is_quality_content(fallback_result):
            logger.info(f"[Smart] Fallback succeeded: {fallback_name}")
            return fallback_result
        else:
            logger.warning(f"[Smart] Fallback also failed/poor quality, returning primary result")
            return result

    return result

def _is_quality_content(result: CrawlResponse) -> bool:
    """Check if crawl result has quality content"""
    if result.markdown:
        # For Crawl4AI results
        if len(result.markdown) < 200:
            return False
        # Check for common error patterns
        error_patterns = ['access denied', 'captcha', 'please enable javascript', '403 forbidden']
        markdown_lower = result.markdown.lower()
        if any(pattern in markdown_lower for pattern in error_patterns):
            return False
        return True
    elif result.html:
        # For Playwright results
        if len(result.html) < 500:
            return False
        return True
    return False


def _extract_product_content(markdown: str, max_length: int = 15000) -> str:
    """
    Extract only product-relevant content from markdown.
    Generic filter that removes:
    - Lines containing markdown links (navigation, menus, country selectors)
    - Common website noise (cookie consent, payment methods, etc.)

    Returns clean text focused on product info.
    """
    if not markdown:
        return ""

    original_len = len(markdown)
    lines = markdown.split('\n')
    filtered_lines = []

    # Pattern to detect markdown links: [text](url) or ![alt](url)
    link_pattern = re.compile(r'\[.*?\]\(.*?\)')

    # Generic noise patterns (not brand-specific)
    noise_patterns = [
        # Cookie/privacy
        'skip to content', 'we value your privacy', 'manage preferences',
        'accept all', 'reject all', 'cookie policy', 'cookie settings',
        'privacy policy', 'terms of service', 'terms and conditions',
        # Footer
        'powered by shopify', 'powered by woocommerce', 'powered by squarespace',
        'all rights reserved', 'copyright ©',
        # Country/region selectors
        'country/region', 'select your country', 'choose your location',
        'currency selector', 'language selector',
        # Payment
        'payment methods', 'we accept',
        # Nav hints
        'choosing a selection results', 'opens in a new window',
        'back to top', 'scroll to top',
    ]

    for line in lines:
        line_stripped = line.strip()
        line_lower = line_stripped.lower()

        # Skip empty lines
        if not line_stripped:
            continue

        # Skip lines containing markdown links - these are navigation/menu items
        if link_pattern.search(line):
            continue

        # Skip noise patterns
        if any(noise in line_lower for noise in noise_patterns):
            continue

        # Skip lines that are just bullet points with very short text (menu items)
        if line_stripped.startswith('*') and len(line_stripped) < 30:
            continue

        # Skip single uppercase words (nav items like "SHOP", "MENU")
        if line_stripped.isupper() and len(line_stripped) < 20 and ' ' not in line_stripped:
            continue

        # Skip lines that look like country+currency selectors (e.g., "USD $", "GBP £")
        if re.match(r'^[A-Z]{2,3}\s*[\$£€¥₹₽₩฿]+\s*$', line_stripped):
            continue

        filtered_lines.append(line)

    result = '\n'.join(filtered_lines)

    # Clean up multiple newlines
    result = re.sub(r'\n{3,}', '\n\n', result)

    # Truncate if still too long
    if len(result) > max_length:
        result = result[:max_length]
        last_period = result.rfind('.')
        if last_period > max_length * 0.8:
            result = result[:last_period + 1]

    logger.info(f"Filtered markdown: {original_len} -> {len(result)} chars (removed {original_len - len(result)} chars of noise)")
    return result


# Endpoint: Fetch Raw Content (for blocked sitemaps, XML, etc.)
@app.post("/fetch/raw")
async def fetch_raw(req: CrawlRequest):
    """
    Fetch raw page content using Playwright (bypasses Cloudflare).
    Returns raw HTML/XML content as-is.
    Used for fetching sitemaps blocked by Cloudflare.
    """
    start_time = time.time()
    logger.info(f"[Fetch] Fetching raw content: {req.url}")

    try:
        async with async_playwright() as p:
            browser = await p.chromium.launch(
                headless=True,
                args=['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
            )

            context = await browser.new_context(
                viewport={'width': 1920, 'height': 1080},
                user_agent='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
            )

            page = await context.new_page()
            await page.goto(req.url, wait_until="networkidle", timeout=req.wait_timeout)

            # Get raw content - for XML, the browser shows it in a special viewer
            # We need to get the original source, not the rendered HTML
            content = await page.content()

            # Try to extract XML from browser's XML viewer
            # Chrome wraps XML in: <div id="webkit-xml-viewer-source-xml">...</div>
            xml_content = await page.evaluate("""
                () => {
                    // Check if it's an XML document displayed in browser
                    const xmlViewer = document.querySelector('#webkit-xml-viewer-source-xml');
                    if (xmlViewer) {
                        return xmlViewer.innerHTML;
                    }
                    // Firefox wraps in similar way
                    const pre = document.querySelector('pre');
                    if (pre && document.contentType && document.contentType.includes('xml')) {
                        return pre.textContent;
                    }
                    // If it's raw text/xml displayed, get body content
                    if (document.body && document.body.children.length === 1) {
                        const firstChild = document.body.children[0];
                        if (firstChild.tagName === 'PRE') {
                            return firstChild.textContent;
                        }
                    }
                    return null;
                }
            """)

            await browser.close()

            duration = int((time.time() - start_time) * 1000)

            # Return XML if we extracted it, otherwise return full HTML
            final_content = xml_content if xml_content else content

            logger.info(f"[Fetch] Got {len(final_content)} chars in {duration}ms: {req.url}")

            return {
                "url": req.url,
                "success": True,
                "content": final_content,
                "is_xml": xml_content is not None,
                "duration_ms": duration,
                "content_length": len(final_content)
            }

    except Exception as e:
        duration = int((time.time() - start_time) * 1000)
        logger.error(f"[Fetch] Failed: {req.url} - {str(e)}")
        return {
            "url": req.url,
            "success": False,
            "error": str(e),
            "duration_ms": duration
        }


# Main
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8081,
        reload=True,
        log_level="info"
    )
