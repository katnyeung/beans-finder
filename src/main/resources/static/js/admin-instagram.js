// Instagram Admin JavaScript

const API_BASE = '/api/admin/instagram';
let currentPostId = null;
let currentTab = 'drafts';

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    loadStatus();
    loadDrafts();
});

// ==================== Tab Management ====================

function showTab(tabName) {
    currentTab = tabName;

    // Update tab buttons
    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
    document.querySelector(`[onclick="showTab('${tabName}')"]`).classList.add('active');

    // Update tab content
    document.querySelectorAll('.tab-content').forEach(content => content.classList.remove('active'));
    document.getElementById(`${tabName}-tab`).classList.add('active');

    // Load content
    switch (tabName) {
        case 'drafts':
            loadDrafts();
            break;
        case 'scheduled':
            loadScheduled();
            break;
        case 'posted':
            loadPosted();
            break;
        case 'rejected':
            loadRejected();
            break;
    }
}

// ==================== Data Loading ====================

async function loadStatus() {
    try {
        const response = await fetch(`${API_BASE}/status`);
        if (!response.ok) throw new Error('Failed to load status');
        const data = await response.json();

        document.getElementById('stat-draft').textContent = data.postCounts?.draft || 0;
        document.getElementById('stat-scheduled').textContent = data.postCounts?.scheduled || 0;
        document.getElementById('stat-posted').textContent = data.postCounts?.posted || 0;
        document.getElementById('stat-reddit').textContent = data.redditCacheStats?.totalPosts || 0;
    } catch (error) {
        console.error('Error loading status:', error);
    }
}

async function loadDrafts() {
    const container = document.getElementById('drafts-container');
    container.innerHTML = '<div class="loading"></div>';

    try {
        const response = await fetch(`${API_BASE}/drafts`);
        if (!response.ok) throw new Error('Failed to load drafts');
        const posts = await response.json();

        if (posts.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <h3>No drafts</h3>
                    <p>Click "Generate Post" to create new content</p>
                </div>
            `;
            return;
        }

        container.innerHTML = posts.map(post => renderPostCard(post, 'draft')).join('');
    } catch (error) {
        container.innerHTML = `<div class="empty-state"><p>Error loading drafts: ${error.message}</p></div>`;
    }
}

async function loadScheduled() {
    const container = document.getElementById('scheduled-container');
    container.innerHTML = '<div class="loading"></div>';

    try {
        const response = await fetch(`${API_BASE}/scheduled`);
        if (!response.ok) throw new Error('Failed to load scheduled');
        const posts = await response.json();

        if (posts.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <h3>No scheduled posts</h3>
                    <p>Approve drafts to schedule them</p>
                </div>
            `;
            return;
        }

        container.innerHTML = posts.map(post => renderPostCard(post, 'scheduled')).join('');
    } catch (error) {
        container.innerHTML = `<div class="empty-state"><p>Error loading scheduled: ${error.message}</p></div>`;
    }
}

async function loadPosted() {
    const container = document.getElementById('posted-container');
    container.innerHTML = '<div class="loading"></div>';

    try {
        const response = await fetch(`${API_BASE}/posted`);
        if (!response.ok) throw new Error('Failed to load posted');
        const posts = await response.json();

        if (posts.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <h3>No posted content yet</h3>
                    <p>Published posts will appear here</p>
                </div>
            `;
            return;
        }

        container.innerHTML = posts.map(post => renderPostCard(post, 'posted')).join('');
    } catch (error) {
        container.innerHTML = `<div class="empty-state"><p>Error loading posted: ${error.message}</p></div>`;
    }
}

async function loadRejected() {
    const container = document.getElementById('rejected-container');
    container.innerHTML = '<div class="loading"></div>';

    try {
        const response = await fetch(`${API_BASE}/rejected`);
        if (!response.ok) throw new Error('Failed to load rejected');
        const posts = await response.json();

        if (posts.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <h3>No rejected posts</h3>
                </div>
            `;
            return;
        }

        container.innerHTML = posts.map(post => renderPostCard(post, 'rejected')).join('');
    } catch (error) {
        container.innerHTML = `<div class="empty-state"><p>Error loading rejected: ${error.message}</p></div>`;
    }
}

// ==================== Rendering ====================

function renderPostCard(post, type) {
    const imageHtml = post.imageUrl
        ? `<img src="${escapeHtml(post.imageUrl)}" alt="Post image" class="post-image">`
        : `<div class="post-image-placeholder">No Image</div>`;

    const captionPreview = post.caption?.length > 200
        ? post.caption.substring(0, 200) + '...'
        : post.caption;

    let actionsHtml = '';
    if (type === 'draft') {
        actionsHtml = `
            <button class="btn btn-primary btn-action" onclick="openReviewModal(${post.id})">Review</button>
            <button class="btn btn-secondary btn-action" onclick="deletePost(${post.id})">Delete</button>
        `;
    } else if (type === 'scheduled') {
        actionsHtml = `
            <button class="btn btn-primary btn-action" onclick="publishNow(${post.id})">Publish Now</button>
            <button class="btn btn-secondary btn-action" onclick="openReviewModal(${post.id})">Edit</button>
        `;
    } else if (type === 'posted') {
        actionsHtml = `
            <a href="https://instagram.com/p/${post.instagramMediaId}" target="_blank" class="btn btn-secondary btn-action">View on Instagram</a>
        `;
    }

    const scheduledInfo = post.scheduledAt
        ? `<span>Scheduled: ${formatDateTime(post.scheduledAt)}</span>`
        : '';
    const postedInfo = post.postedAt
        ? `<span>Posted: ${formatDateTime(post.postedAt)}</span>`
        : '';

    return `
        <div class="post-card">
            ${imageHtml}
            <div class="post-content">
                <div class="post-caption">${escapeHtml(captionPreview || '')}</div>
                <div class="post-meta">
                    <span class="status-badge status-${post.status}">${post.status}</span>
                    <span>Created: ${formatDateTime(post.createdAt)}</span>
                    ${scheduledInfo}
                    ${postedInfo}
                </div>
            </div>
            <div class="post-actions">
                ${actionsHtml}
            </div>
        </div>
    `;
}

function formatDateTime(dateStr) {
    if (!dateStr) return '';
    const date = new Date(dateStr);
    return date.toLocaleString('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
    });
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ==================== Modal ====================

async function openReviewModal(postId) {
    currentPostId = postId;

    try {
        const response = await fetch(`${API_BASE}/posts/${postId}`);
        if (!response.ok) throw new Error('Failed to load post');
        const post = await response.json();

        document.getElementById('modal-image').src = post.imageUrl || '';
        document.getElementById('modal-caption').value = post.caption || '';

        // Set default schedule to tomorrow at noon
        const tomorrow = new Date();
        tomorrow.setDate(tomorrow.getDate() + 1);
        tomorrow.setHours(12, 0, 0, 0);
        const scheduleInput = document.getElementById('modal-schedule');
        scheduleInput.value = tomorrow.toISOString().slice(0, 16);

        document.getElementById('review-modal').classList.add('active');
    } catch (error) {
        alert('Failed to load post: ' + error.message);
    }
}

function closeModal() {
    document.getElementById('review-modal').classList.remove('active');
    currentPostId = null;
}

// ==================== Actions ====================

async function saveCaption() {
    if (!currentPostId) return;

    const caption = document.getElementById('modal-caption').value;

    try {
        const response = await fetch(`${API_BASE}/posts/${currentPostId}/caption`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ caption })
        });

        if (!response.ok) throw new Error('Failed to save caption');
        alert('Caption saved!');
        refreshCurrentTab();
    } catch (error) {
        alert('Error saving caption: ' + error.message);
    }
}

async function approveAndSchedule() {
    if (!currentPostId) return;

    const scheduledAt = document.getElementById('modal-schedule').value;
    if (!scheduledAt) {
        alert('Please select a schedule time');
        return;
    }

    try {
        // First save caption
        const caption = document.getElementById('modal-caption').value;
        await fetch(`${API_BASE}/posts/${currentPostId}/caption`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ caption })
        });

        // Then schedule
        const response = await fetch(`${API_BASE}/posts/${currentPostId}/schedule`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ scheduledAt })
        });

        if (!response.ok) throw new Error('Failed to schedule post');

        alert('Post scheduled!');
        closeModal();
        loadStatus();
        refreshCurrentTab();
    } catch (error) {
        alert('Error scheduling post: ' + error.message);
    }
}

async function rejectPost() {
    if (!currentPostId) return;

    const reason = prompt('Rejection reason (optional):');

    try {
        const response = await fetch(`${API_BASE}/posts/${currentPostId}/reject`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ reason: reason || '' })
        });

        if (!response.ok) throw new Error('Failed to reject post');

        closeModal();
        loadStatus();
        refreshCurrentTab();
    } catch (error) {
        alert('Error rejecting post: ' + error.message);
    }
}

async function regenerateImage() {
    if (!currentPostId) return;

    if (!confirm('Generate a new image? This will cost approximately $0.04')) return;

    try {
        const response = await fetch(`${API_BASE}/posts/${currentPostId}/regenerate-image`, {
            method: 'POST'
        });

        if (!response.ok) throw new Error('Failed to regenerate image');
        const post = await response.json();

        document.getElementById('modal-image').src = post.imageUrl || '';
        alert('Image regenerated!');
    } catch (error) {
        alert('Error regenerating image: ' + error.message);
    }
}

async function deletePost(postId) {
    if (!confirm('Delete this post?')) return;

    try {
        const response = await fetch(`${API_BASE}/posts/${postId}`, {
            method: 'DELETE'
        });

        if (!response.ok) throw new Error('Failed to delete post');

        loadStatus();
        refreshCurrentTab();
    } catch (error) {
        alert('Error deleting post: ' + error.message);
    }
}

async function publishNow(postId) {
    if (!confirm('Publish this post to Instagram now?')) return;

    try {
        const response = await fetch(`${API_BASE}/posts/${postId}/publish-now`, {
            method: 'POST'
        });

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || 'Failed to publish');
        }

        alert('Post published to Instagram!');
        loadStatus();
        refreshCurrentTab();
    } catch (error) {
        alert('Error publishing post: ' + error.message);
    }
}

// ==================== Admin Actions ====================

async function crawlReddit() {
    if (!confirm('Start Reddit crawl? This will fetch the latest posts from coffee subreddits.')) return;

    try {
        const response = await fetch(`${API_BASE}/crawl-reddit`, { method: 'POST' });
        if (!response.ok) throw new Error('Failed to start crawl');
        const result = await response.json();

        alert(`Reddit crawl complete! ${result.postsCrawled} posts cached.`);
        loadStatus();
    } catch (error) {
        alert('Error crawling Reddit: ' + error.message);
    }
}

async function generatePost() {
    if (!confirm('Generate a new Instagram post? This will use AI to create content from cached data.')) return;

    try {
        const response = await fetch(`${API_BASE}/generate`, { method: 'POST' });
        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || 'Failed to generate post');
        }

        alert('New post generated! Check the Drafts tab.');
        loadStatus();
        showTab('drafts');
    } catch (error) {
        alert('Error generating post: ' + error.message);
    }
}

function refreshCurrentTab() {
    showTab(currentTab);
}
