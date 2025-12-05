/**
 * Common UI components shared across all pages
 */

// Footer template
function renderFooter() {
    const footer = document.createElement('footer');
    footer.innerHTML = `
        <div class="container">
            <p>&copy; 2024 Graphee.link - Specialty Coffee Discovery</p>
            <p class="disclaimer">Data is collected automatically and may be incomplete or inaccurate. We strive to provide the best matches, but cannot guarantee perfection. Always verify details on the roaster's website before purchasing.</p>
            <p class="contact">For questions or cooperation, please contact <a href="mailto:info@graphee.link">info@graphee.link</a></p>
        </div>
    `;
    document.body.appendChild(footer);
}

// Auto-render footer when DOM is ready
document.addEventListener('DOMContentLoaded', renderFooter);
