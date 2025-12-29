/**
 * Common UI components shared across all pages
 */

// Current user (populated by checkAuth)
let currentUser = null;

/**
 * Check if user is logged in and update navbar
 */
async function checkAuth() {
    try {
        const response = await fetch('/api/auth/me');
        if (response.ok) {
            const text = await response.text();
            // Only parse if we got content
            if (text && text.trim()) {
                currentUser = JSON.parse(text);

                // If just logged in and we have a saved redirect URL, go there
                if (currentUser) {
                    const redirectUrl = localStorage.getItem('auth_redirect_url');
                    if (redirectUrl) {
                        localStorage.removeItem('auth_redirect_url');
                        // Only redirect if we're on the home page (means we just came from OAuth)
                        if (window.location.pathname === '/' || window.location.pathname === '/index.html') {
                            window.location.href = redirectUrl;
                            return;
                        }
                    }
                }
            } else {
                currentUser = null;
            }
        } else {
            currentUser = null;
        }
    } catch (e) {
        console.debug('Auth check:', e.message);
        currentUser = null;
    }
    // Always update navbar (show Sign In or user menu)
    updateNavbarAuth();
}

/**
 * Sign in - save current page and redirect to Google OAuth
 */
function signIn() {
    // Save current path+query to redirect back after login
    const currentPath = window.location.pathname + window.location.search;
    localStorage.setItem('auth_redirect_url', currentPath);
    window.location.href = '/oauth2/authorization/google';
}

/**
 * Update navbar to show login/logout button
 */
function updateNavbarAuth() {
    const navLinks = document.querySelector('.nav-links');
    if (!navLinks) return;

    // Remove existing auth element if any
    const existingAuth = navLinks.querySelector('.nav-auth');
    if (existingAuth) existingAuth.remove();

    // Create auth element
    const authEl = document.createElement('div');
    authEl.className = 'nav-auth';

    if (currentUser) {
        // Logged in - show user info + dropdown
        authEl.innerHTML = `
            <div class="user-menu">
                <button class="user-menu-toggle">
                    <img src="${currentUser.pictureUrl || '/graphee_logo.png'}" alt="${currentUser.name}" class="user-avatar">
                    <span class="user-name">${currentUser.name?.split(' ')[0] || 'User'}</span>
                    <span class="dropdown-arrow">▼</span>
                </button>
                <div class="user-dropdown">
                    <a href="/my-coffees.html">☕ My Coffees</a>
                    <button class="logout-btn" onclick="logout()">🚪 Sign Out</button>
                </div>
            </div>
        `;

        // Add dropdown toggle behavior
        setTimeout(() => {
            const toggle = authEl.querySelector('.user-menu-toggle');
            const dropdown = authEl.querySelector('.user-dropdown');
            if (toggle && dropdown) {
                toggle.addEventListener('click', (e) => {
                    e.stopPropagation();
                    dropdown.classList.toggle('open');
                });
                document.addEventListener('click', () => {
                    dropdown.classList.remove('open');
                });
            }
        }, 0);
    } else {
        // Not logged in - show sign in button
        authEl.innerHTML = `
            <a href="#" onclick="signIn()" class="btn-signin" title="Sign in with Google to save your favorite coffees">
                <svg width="18" height="18" viewBox="0 0 24 24"><path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/><path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/><path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/><path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/></svg>
                Sign In
            </a>
        `;
    }

    navLinks.appendChild(authEl);
}

/**
 * Logout user
 */
async function logout() {
    try {
        await fetch('/api/auth/logout', { method: 'POST' });
        currentUser = null;
        updateNavbarAuth();
        // Refresh if on protected page
        if (window.location.pathname === '/my-coffees.html') {
            window.location.href = '/';
        }
    } catch (e) {
        console.error('Logout failed:', e);
    }
}

/**
 * Get current user (for other pages to use)
 */
function getCurrentUser() {
    return currentUser;
}

// Footer template
function renderFooter() {
    const footer = document.createElement('footer');
    footer.innerHTML = `
        <div class="container">
            <p>&copy; 2024 Graphee.link - Specialty Coffee Discovery</p>
            <p class="disclaimer">Data is collected automatically and may be incomplete or inaccurate. We strive to provide the best matches, but cannot guarantee perfection. Always verify details on the roaster's website before purchasing.</p>
            <p class="contact">For questions or cooperation, please contact <a href="mailto:katnyeung@gmail.com">katnyeung@gmail.com</a></p>
        </div>
    `;
    document.body.appendChild(footer);
}

// Auto-render footer and check auth when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    renderFooter();
    checkAuth();
});

// Mobile dropdown toggle (adds click support for touch devices)
// Desktop hover still works - this just ADDS click support
document.addEventListener('DOMContentLoaded', () => {
    const dropdownToggle = document.querySelector('.nav-dropdown-toggle');
    const dropdown = document.querySelector('.nav-dropdown');

    if (dropdownToggle && dropdown) {
        dropdownToggle.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            dropdown.classList.toggle('open');
        });

        // Close when clicking outside
        document.addEventListener('click', (e) => {
            if (!dropdown.contains(e.target)) {
                dropdown.classList.remove('open');
            }
        });

        // Close when clicking a menu item
        dropdown.querySelectorAll('.dropdown-menu a').forEach(link => {
            link.addEventListener('click', () => {
                dropdown.classList.remove('open');
            });
        });
    }
});
